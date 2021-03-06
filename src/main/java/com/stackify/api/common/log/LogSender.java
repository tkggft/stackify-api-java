/*
 * Copyright 2014 Stackify
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.stackify.api.common.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stackify.api.ErrorItem;
import com.stackify.api.LogMsg;
import com.stackify.api.LogMsgGroup;
import com.stackify.api.common.ApiConfiguration;
import com.stackify.api.common.http.HttpClient;
import com.stackify.api.common.http.HttpException;
import com.stackify.api.common.http.HttpResendQueue;
import com.stackify.api.common.mask.Masker;
import com.stackify.api.common.util.Preconditions;
import com.stackify.api.common.util.SkipJsonUtil;
import lombok.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.Map;

/**
 * LogSender
 * @author Eric Martin
 */
public class LogSender {

	/**
	 * The service logger
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(LogSender.class);

	/**
	 * REST path for log save
	 */
	private static final String LOG_SAVE_PATH = "/Log/Save";

	/**
	 * The API configuration
	 */
	private final ApiConfiguration apiConfig;

	/**
	 * JSON object mapper
	 */
	private final ObjectMapper objectMapper;

	/**
	 * The queue of requests to be retransmitted (max of 20 batches of 100 messages)
	 */
	private final HttpResendQueue resendQueue = new HttpResendQueue(20);


	private final Masker masker;

	private final boolean skipJson;

	/**
	 * Default constructor
	 * @param apiConfig API configuration
	 * @param objectMapper JSON object mapper
	 * @param masker Message Masker
	 * @param skipJson Messages detected w/ json will have the #SKIPJSON tag added
	 */
	public LogSender(@NonNull final ApiConfiguration apiConfig,
					 @NonNull final ObjectMapper objectMapper,
					 final Masker masker,
					 final boolean skipJson) {
		this.apiConfig = apiConfig;
		this.objectMapper = objectMapper;
		this.masker = masker;
		this.skipJson = skipJson;
	}

	/**
	 * Default constructor
	 * @param apiConfig API configuration
	 * @param objectMapper JSON object mapper
	 * @param masker Message Masker
	 */
	public LogSender(@NonNull final ApiConfiguration apiConfig,
					 @NonNull final ObjectMapper objectMapper,
					 final Masker masker) {
		this(apiConfig, objectMapper, masker, false);
	}

	private void executeSkipJsonTag(final LogMsgGroup group) {
		if (skipJson) {
			if (group.getMsgs().size() > 0) {
				for (LogMsg logMsg : group.getMsgs()) {
					if (logMsg.getEx() != null) {
						executeSkipJsonTag(logMsg.getEx().getError());
					}
					logMsg.setData(SkipJsonUtil.execute(logMsg.getData()));
					logMsg.setMsg(SkipJsonUtil.execute(logMsg.getMsg()));
				}
			}

		}
	}

	private void executeSkipJsonTag(final ErrorItem errorItem) {
		if (skipJson) {
			if (errorItem != null) {
				errorItem.setMessage(SkipJsonUtil.execute(errorItem.getMessage()));
				if (errorItem.getData() != null) {
					for (Map.Entry<String, String> entry : errorItem.getData().entrySet()) {
						entry.setValue(SkipJsonUtil.execute(entry.getValue()));
					}
				}
				executeSkipJsonTag(errorItem.getInnerError());
			}
		}
	}

	/**
	 * Applies masking to passed in LogMsgGroup.
	 */
	private void executeMask(final LogMsgGroup group) {
		if (masker != null) {
			if (group.getMsgs().size() > 0) {
				for (LogMsg logMsg : group.getMsgs()) {
					if (logMsg.getEx() != null) {
						executeMask(logMsg.getEx().getError());
					}
					logMsg.setData(masker.mask(logMsg.getData()));
					logMsg.setMsg(masker.mask(logMsg.getMsg()));
				}
			}
		}
	}

	private void executeMask(final ErrorItem errorItem) {
        if (errorItem != null) {
            errorItem.setMessage(masker.mask(errorItem.getMessage()));
            if (errorItem.getData() != null) {
                for (Map.Entry<String, String> entry : errorItem.getData().entrySet()) {
                    entry.setValue(masker.mask(entry.getValue()));
                }
            }
            executeMask(errorItem.getInnerError());
        }
	}

	/**
	 * Sends a group of log messages to Stackify
	 * @param group The log message group
	 * @return The HTTP status code returned from the HTTP POST
	 * @throws IOException
	 */
	public int send(final LogMsgGroup group) throws IOException {
		Preconditions.checkNotNull(group);

		executeMask(group);
		executeSkipJsonTag(group);

		HttpClient httpClient = new HttpClient(apiConfig);

		// retransmit any logs on the resend queue

		resendQueue.drain(httpClient, LOG_SAVE_PATH, true);

		// convert to json bytes

		byte[] jsonBytes = objectMapper.writer().writeValueAsBytes(group);

		// post to stackify

		int statusCode = HttpURLConnection.HTTP_INTERNAL_ERROR;

		try {
			httpClient.post(LOG_SAVE_PATH, jsonBytes, true);
			statusCode = HttpURLConnection.HTTP_OK;
		} catch (IOException t) {
			LOGGER.info("Queueing logs for retransmission due to IOException");
			resendQueue.offer(jsonBytes, t);
			throw t;
		} catch (HttpException e) {
			statusCode = e.getStatusCode();
			LOGGER.info("Queueing logs for retransmission due to HttpException", e);
			resendQueue.offer(jsonBytes, e);
		}

		return statusCode;
	}
}
