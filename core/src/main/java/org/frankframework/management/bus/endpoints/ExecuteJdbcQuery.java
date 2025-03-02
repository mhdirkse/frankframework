/*
   Copyright 2022-2023 WeAreFrank!

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package org.frankframework.management.bus.endpoints;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Logger;
import org.frankframework.management.bus.TopicSelector;
import org.springframework.http.MediaType;
import org.springframework.messaging.Message;
import org.springframework.util.MimeType;

import org.frankframework.jdbc.DirectQuerySender;
import org.frankframework.jdbc.IDataSourceFactory;
import org.frankframework.jdbc.JdbcQuerySenderBase.QueryType;
import org.frankframework.jdbc.transformer.QueryOutputToCSV;
import org.frankframework.jdbc.transformer.QueryOutputToJson;
import org.frankframework.jndi.JndiDataSourceFactory;
import org.frankframework.management.bus.ActionSelector;
import org.frankframework.management.bus.BusAction;
import org.frankframework.management.bus.BusAware;
import org.frankframework.management.bus.BusException;
import org.frankframework.management.bus.BusMessageUtils;
import org.frankframework.management.bus.BusTopic;
import org.frankframework.management.bus.JsonResponseMessage;
import org.frankframework.management.bus.StringResponseMessage;
import org.frankframework.util.LogUtil;

@BusAware("frank-management-bus")
@TopicSelector(BusTopic.JDBC)
public class ExecuteJdbcQuery extends BusEndpointBase {
	private Logger secLog = LogUtil.getLogger("SEC");

	public enum ResultType {
		CSV, XML, JSON;
	}

	@ActionSelector(BusAction.GET)
	public Message<String> getJdbcInfo(Message<?> message) {
		Map<String, Object> result = new HashMap<>();

		IDataSourceFactory dataSourceFactory = getBean("dataSourceFactory", IDataSourceFactory.class);
		List<String> dataSourceNames = dataSourceFactory.getDataSourceNames();
		dataSourceNames.sort(Comparator.naturalOrder()); //AlphaNumeric order
		result.put("datasources", dataSourceNames);

		List<String> resultTypes = new ArrayList<>();
		for(ResultType type : ResultType.values()) {
			resultTypes.add(type.name().toLowerCase());
		}
		result.put("resultTypes", resultTypes);

		List<String> queryTypes = new ArrayList<>();
		queryTypes.add("AUTO");
		queryTypes.add(QueryType.SELECT.toString());
		queryTypes.add(QueryType.OTHER.toString());
		result.put("queryTypes", queryTypes);

		return new JsonResponseMessage(result);
	}

	@ActionSelector(BusAction.MANAGE)
	public StringResponseMessage executeJdbcQuery(Message<?> message) {
		String datasource = BusMessageUtils.getHeader(message, BusMessageUtils.HEADER_DATASOURCE_NAME_KEY, JndiDataSourceFactory.GLOBAL_DEFAULT_DATASOURCE_NAME);
		String queryType = BusMessageUtils.getHeader(message, "queryType", "select");
		String query = BusMessageUtils.getHeader(message, "query");
		boolean trimSpaces = BusMessageUtils.getBooleanHeader(message, "trimSpaces", false);
		boolean avoidLocking = BusMessageUtils.getBooleanHeader(message, "avoidLocking", false);
		ResultType resultType = BusMessageUtils.getEnumHeader(message, "resultType", ResultType.class, ResultType.XML);

		return doExecute(datasource, queryType, query, trimSpaces, avoidLocking, resultType);
	}

	private StringResponseMessage doExecute(String datasource, String queryType, String query, boolean trimSpaces, boolean avoidLocking, ResultType resultType) {
		secLog.info(String.format("executing query [%s] on datasource [%s] queryType [%s] avoidLocking [%s]", query, datasource, queryType, avoidLocking));

		DirectQuerySender qs = createBean(DirectQuerySender.class);
		String result;
		MimeType mimetype;

		try {
			qs.setName("ExecuteJdbc QuerySender");
			qs.setDatasourceName(datasource);
			qs.setQueryType(queryType);
			qs.setTrimSpaces(trimSpaces);
			qs.setAvoidLocking(avoidLocking);
			qs.setBlobSmartGet(true);
			qs.setPrettyPrint(true);
			qs.configure(true);
			qs.open();

			org.frankframework.stream.Message message = qs.sendMessageOrThrow(new org.frankframework.stream.Message(query), null);

			switch (resultType) {
			case CSV:
				result = new QueryOutputToCSV().parse(message);
				mimetype = MediaType.TEXT_PLAIN;
				break;
			case JSON:
				result = new QueryOutputToJson().parse(message);
				mimetype = MediaType.APPLICATION_JSON;
				break;
			case XML:
			default:
				result = message.asString();
				mimetype = MediaType.APPLICATION_XML;
				break;
			}
			message.close();
		} catch (Exception e) {
			throw new BusException("error executing query", e);
		} finally {
			qs.close();
		}

		return new StringResponseMessage(result, mimetype);
	}
}
