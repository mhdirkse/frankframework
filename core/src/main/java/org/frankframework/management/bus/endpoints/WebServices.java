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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;

import javax.xml.stream.XMLStreamException;

import org.apache.commons.lang3.StringUtils;
import org.frankframework.management.bus.TopicSelector;
import org.springframework.http.MediaType;
import org.springframework.messaging.Message;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonWriter;
import jakarta.json.JsonWriterFactory;
import jakarta.json.stream.JsonGenerator;
import lombok.Getter;

import org.frankframework.configuration.Configuration;
import org.frankframework.configuration.ConfigurationException;
import org.frankframework.core.Adapter;
import org.frankframework.core.IAdapter;
import org.frankframework.core.IListener;
import org.frankframework.http.RestListener;
import org.frankframework.http.WebServiceListener;
import org.frankframework.http.rest.ApiDispatchConfig;
import org.frankframework.http.rest.ApiListener;
import org.frankframework.http.rest.ApiListener.HttpMethod;
import org.frankframework.http.rest.ApiServiceDispatcher;
import org.frankframework.management.bus.ActionSelector;
import org.frankframework.management.bus.BinaryResponseMessage;
import org.frankframework.management.bus.BusAction;
import org.frankframework.management.bus.BusAware;
import org.frankframework.management.bus.BusException;
import org.frankframework.management.bus.BusMessageUtils;
import org.frankframework.management.bus.BusTopic;
import org.frankframework.management.bus.JsonResponseMessage;
import org.frankframework.management.bus.StringResponseMessage;
import org.frankframework.receivers.Receiver;
import org.frankframework.soap.WsdlGenerator;
import org.frankframework.soap.WsdlGeneratorUtils;

@BusAware("frank-management-bus")
@TopicSelector(BusTopic.WEBSERVICES)
public class WebServices extends BusEndpointBase {
	private enum ServiceType {
		WSDL, OPENAPI
	}

	@ActionSelector(BusAction.GET)
	public Message<String> getWebServices(Message<?> message) {
		Map<String, Object> returnMap = new HashMap<>();

		returnMap.put("services", getRestListeners());
		returnMap.put("wsdls", getWsdls());
		returnMap.put("apiListeners", getApiListeners());

		return new JsonResponseMessage(returnMap);
	}

	@ActionSelector(BusAction.DOWNLOAD)
	public Message<?> getService(Message<?> message) {
		ServiceType type = BusMessageUtils.getEnumHeader(message, "type", ServiceType.class);
		if(type == ServiceType.OPENAPI) {
			return getOpenApiSpec(message);
		} else {
			return getWSDL(message);
		}
	}

	public StringResponseMessage getOpenApiSpec(Message<?> message) {
		String uri = BusMessageUtils.getHeader(message, "uri", null);

		JsonObject jsonSchema = null;
		ApiServiceDispatcher dispatcher = ApiServiceDispatcher.getInstance();
		if(uri != null) {
			ApiDispatchConfig apiConfig = dispatcher.findConfigForUri(uri);
			if(apiConfig == null) {
				throw new BusException("unable to find Dispatch configuration for uri");
			}
			jsonSchema = dispatcher.generateOpenApiJsonSchema(apiConfig, null);
		} else {
			jsonSchema = dispatcher.generateOpenApiJsonSchema(null);
		}

		Map<String, Boolean> config = new HashMap<>();
		config.put(JsonGenerator.PRETTY_PRINTING, true);
		JsonWriterFactory factory = Json.createWriterFactory(config);
		StringWriter writer = new StringWriter();
		try (JsonWriter jsonWriter = factory.createWriter(writer)) {
			jsonWriter.write(jsonSchema);
		}

		return new StringResponseMessage(writer.toString(), MediaType.APPLICATION_JSON);
	}

	public BinaryResponseMessage getWSDL(Message<?> message) {
		boolean indent = BusMessageUtils.getBooleanHeader(message, "indent", true);
		boolean useIncludes = BusMessageUtils.getBooleanHeader(message, "useIncludes", false);
		boolean zip = BusMessageUtils.getBooleanHeader(message, "zip", false);

		String configurationName = BusMessageUtils.getHeader(message, BusMessageUtils.HEADER_CONFIGURATION_NAME_KEY);
		String adapterName = BusMessageUtils.getHeader(message, BusMessageUtils.HEADER_ADAPTER_NAME_KEY);
		Adapter adapter = getAdapterByName(configurationName, adapterName);

		String generationInfo = "by FrankConsole";
		WsdlGenerator wsdl = null;
		try {
			wsdl = new WsdlGenerator(adapter.getPipeLine(), generationInfo);
			wsdl.setIndent(indent);
			wsdl.setUseIncludes(useIncludes||zip);
			wsdl.init();
		} catch (Exception e) {
			throw new BusException("unable to create WSDL generator", e);
		}

		try {
			String servletName = getServiceEndpoint(adapter);
			ByteArrayOutputStream boas = new ByteArrayOutputStream();
			if (zip) {
				wsdl.zip(boas, servletName);

				BinaryResponseMessage response = new BinaryResponseMessage(boas.toByteArray(), MediaType.APPLICATION_OCTET_STREAM);
				response.setFilename(adapterName+".zip");
				return response;

			} else {
				wsdl.wsdl(boas, servletName);

				return new BinaryResponseMessage(boas.toByteArray(), MediaType.APPLICATION_XML);
			}
		} catch (IOException | ConfigurationException | XMLStreamException e) {
			throw new BusException("unable to generate WSDL", e);
		}
	}

	private String getServiceEndpoint(IAdapter adapter) {
		String endpoint = "external address of ibis";
		for(Receiver<?> receiver : adapter.getReceivers()) {
			IListener<?> listener = receiver.getListener();
			if(listener instanceof WebServiceListener) {
				String address = ((WebServiceListener) listener).getAddress();
				if(StringUtils.isNotEmpty(address)) {
					endpoint = address;
				} else {
					endpoint = "rpcrouter";
				}
				return "/services/" + endpoint;
			}
		}
		return endpoint;
	}

	private List<ListenerDAO> getApiListeners() {
		List<ListenerDAO> apiListeners = new LinkedList<>();
		SortedMap<String, ApiDispatchConfig> patternClients = ApiServiceDispatcher.getInstance().getPatternClients();
		for (Entry<String, ApiDispatchConfig> client : patternClients.entrySet()) {
			ApiDispatchConfig config = client.getValue();

			Set<HttpMethod> methods = config.getMethods();
			for (HttpMethod method : methods) {
				ApiListener listener = config.getApiListener(method);
				Receiver<?> receiver = listener.getReceiver();
				IAdapter adapter = receiver == null? null : receiver.getAdapter();
				ListenerDAO dao = new ListenerDAO(listener);
				if (adapter!=null) dao.setAdapter(adapter);
				if (receiver!=null) dao.setReceiver(receiver);

				apiListeners.add(dao);
			}
		}
		return apiListeners;
	}

	private List<Map<String, Object>> getWsdls() {
		List<Map<String, Object>> wsdls = new ArrayList<>();
		for (Configuration config : getIbisManager().getConfigurations()) {
			for (Adapter adapter : config.getRegisteredAdapters()) {
				Map<String, Object> wsdlMap = new HashMap<>(3);
				wsdlMap.put("configuration", config.getName());
				wsdlMap.put("adapter", adapter.getName());
				try {
					if(WsdlGeneratorUtils.canProvideWSDL(adapter)) { // check eligibility
						WsdlGenerator wsdl = new WsdlGenerator(adapter.getPipeLine());
						wsdlMap.put("name", wsdl.getName());
						wsdls.add(wsdlMap);
					}
				} catch (Exception e) {
					wsdlMap.put("error", e.getMessage() != null ? e.getMessage() : e.toString());
					wsdls.add(wsdlMap);
				}

			}
		}
		return wsdls;
	}

	public List<ListenerDAO> getRestListeners() {
		List<ListenerDAO> restListeners = new ArrayList<>();

		for (Configuration config : getIbisManager().getConfigurations()) {
			for (Adapter adapter : config.getRegisteredAdapters()) {
				for (Receiver<?> receiver: adapter.getReceivers()) {
					IListener<?> listener = receiver.getListener();
					if (listener instanceof RestListener) {
						ListenerDAO dao = new ListenerDAO((RestListener) listener);
						dao.setAdapter(adapter);
						dao.setReceiver(receiver);
						restListeners.add(dao);
					}
				}
			}
		}
		return restListeners;
	}

	@JsonInclude(Include.NON_NULL)
	public class ListenerDAO {
		private final @Getter String name;
		private final @Getter String method;
		private final @Getter String uriPattern;
		private @Getter String receiver;
		private @Getter String adapter;

		public ListenerDAO(RestListener listener) {
			this.name = listener.getName();
			this.method = listener.getMethod();
			this.uriPattern = listener.getUriPattern();
		}

		public ListenerDAO(ApiListener listener) {
			this.name = listener.getName();
			this.method = listener.getMethod().name();
			this.uriPattern = listener.getUriPattern();
		}

		public void setReceiver(Receiver<?> receiver) {
			this.receiver = receiver.getName();
		}

		public void setAdapter(IAdapter adapter) {
			this.adapter = adapter.getName();
		}
	}
}
