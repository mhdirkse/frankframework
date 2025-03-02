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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.frankframework.dbms.IDbmsSupport;

import org.apache.commons.lang3.StringUtils;
import org.frankframework.management.bus.TopicSelector;
import org.springframework.http.MediaType;
import org.springframework.messaging.Message;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonStructure;

import org.frankframework.core.Adapter;
import org.frankframework.core.IForwardTarget;
import org.frankframework.core.IPipe;
import org.frankframework.core.ISender;
import org.frankframework.core.ITransactionalStorage;
import org.frankframework.core.PipeLine;
import org.frankframework.core.PipeLineSession;
import org.frankframework.core.PipeRunResult;
import org.frankframework.jdbc.DirectQuerySender;
import org.frankframework.jndi.JndiDataSourceFactory;
import org.frankframework.management.bus.BusAware;
import org.frankframework.management.bus.BusException;
import org.frankframework.management.bus.BusMessageUtils;
import org.frankframework.management.bus.BusTopic;
import org.frankframework.management.bus.StringResponseMessage;
import org.frankframework.pipes.MessageSendingPipe;
import org.frankframework.receivers.Receiver;

@BusAware("frank-management-bus")
public class IbisstoreSummary extends BusEndpointBase {

	@TopicSelector(BusTopic.IBISSTORE_SUMMARY)
	public StringResponseMessage showIbisStoreSummary(Message<?> message) {
		String datasource = BusMessageUtils.getHeader(message, BusMessageUtils.HEADER_DATASOURCE_NAME_KEY, JndiDataSourceFactory.GLOBAL_DEFAULT_DATASOURCE_NAME);
		String query = BusMessageUtils.getHeader(message, "query");

		return execute(datasource, query);
	}

	private StringResponseMessage execute(String datasource, String query) {
		String result = "";
		try {
			IbisstoreSummaryQuerySender qs;
			qs = createBean(IbisstoreSummaryQuerySender.class);
			qs.setSlotmap(getSlotmap());
			try {
				qs.setName("QuerySender");
				qs.setDatasourceName(datasource);
				qs.setQueryType("select");
				qs.setBlobSmartGet(true);
				qs.setAvoidLocking(true);
				qs.configure(true);
				qs.open();
				try (org.frankframework.stream.Message message = qs.sendMessageOrThrow(new org.frankframework.stream.Message(query != null ? query : this.getIbisStoreSummaryQuery(qs.getDbmsSupport())), null)) {
					result = message.asString();
				}
			} catch (Throwable t) {
				throw new BusException("An error occurred on executing jdbc query", t);
			} finally {
				qs.close();
			}
		} catch (Exception e) {
			throw new BusException("An error occurred on creating or closing the connection", e);
		}

		String resultObject = "{ \"result\":"+result+"}";
		return new StringResponseMessage(resultObject, MediaType.APPLICATION_JSON);
	}

	private Map<String, SlotIdRecord> getSlotmap() {
		Map<String, SlotIdRecord> slotmap = new HashMap<>();

		for(Adapter adapter: getIbisManager().getRegisteredAdapters()) {
			for (Receiver receiver: adapter.getReceivers()) {
				ITransactionalStorage errorStorage=receiver.getErrorStorage();
				if (errorStorage!=null) {
					String slotId=errorStorage.getSlotId();
					if (StringUtils.isNotEmpty(slotId)) {
						SlotIdRecord sir=new SlotIdRecord(adapter.getName(),receiver.getName(),null);
						String type = errorStorage.getType();
						slotmap.put(type+"/"+slotId,sir);
					}
				}
				ITransactionalStorage messageLog=receiver.getMessageLog();
				if (messageLog!=null) {
					String slotId=messageLog.getSlotId();
					if (StringUtils.isNotEmpty(slotId)) {
						SlotIdRecord sir=new SlotIdRecord(adapter.getName(),receiver.getName(),null);
						String type = messageLog.getType();
						slotmap.put(type+"/"+slotId,sir);
					}
				}
			}
			PipeLine pipeline=adapter.getPipeLine();
			if (pipeline!=null) {
				for (int i=0; i<pipeline.getPipeLineSize(); i++) {
					IPipe pipe=pipeline.getPipe(i);
					if (pipe instanceof MessageSendingPipe) {
						MessageSendingPipe msp=(MessageSendingPipe)pipe;
						ITransactionalStorage messageLog = msp.getMessageLog();
						if (messageLog!=null) {
							String slotId=messageLog.getSlotId();
							if (StringUtils.isNotEmpty(slotId)) {
								SlotIdRecord sir=new SlotIdRecord(adapter.getName(),null,msp.getName());
								String type = messageLog.getType();
								slotmap.put(type+"/"+slotId,sir);
								slotmap.put(slotId,sir);
							}
						} else {
							ISender sender = msp.getSender();
							if(sender instanceof ITransactionalStorage) {
								ITransactionalStorage transactionalStorage = (ITransactionalStorage) sender;
								String slotId=transactionalStorage.getSlotId();
								if (StringUtils.isNotEmpty(slotId)) {
									SlotIdRecord sir=new SlotIdRecord(adapter.getName(),null,msp.getName());
									String type = transactionalStorage.getType();
									slotmap.put(type+"/"+slotId,sir);
									slotmap.put(slotId,sir);
								}
							}
						}
					}
				}
			}
		}
		return slotmap;
	}

	public String getIbisStoreSummaryQuery(IDbmsSupport dbmsSupport) {
		// include a where clause, to make org.frankframework.dbms.MsSqlServerDbmsSupport.prepareQueryTextForNonLockingRead() work
		return "select type, slotid, " + dbmsSupport.getTimestampAsDate("MESSAGEDATE") + " msgdate, count(*) msgcount from IBISSTORE where 1=1 group by slotid, type, " + dbmsSupport.getTimestampAsDate("MESSAGEDATE") + " order by type, slotid, " + dbmsSupport.getTimestampAsDate("MESSAGEDATE");
	}
}

class IbisstoreSummaryQuerySender extends DirectQuerySender {
	private Map<String, SlotIdRecord> slotmap = new HashMap<>();

	public void setSlotmap(Map<String, SlotIdRecord> slotmap) {
		this.slotmap = slotmap;
	}

	@Override
	protected PipeRunResult getResult(ResultSet resultset, Object blobSessionVar, Object clobSessionVar, HttpServletResponse response, String contentType, String contentDisposition, PipeLineSession session, IForwardTarget next) throws SQLException {
		JsonArrayBuilder types = Json.createArrayBuilder();
		String previousType=null;
		JsonObjectBuilder typeBuilder=null;
		JsonArrayBuilder slotsBuilder=null;
		String previousSlot=null;
		JsonObjectBuilder slotBuilder=null;
		JsonArrayBuilder datesBuilder=null;
		int typeslotcount=0;
		int typedatecount=0;
		int typemsgcount=0;
		int slotdatecount=0;
		int slotmsgcount=0;
		while (resultset.next()) {
			String type = resultset.getString("type");
			String slotid = resultset.getString("slotid");
			String date =  resultset.getString("msgdate");
			int count =    resultset.getInt("msgcount");

			if (type==null) {
				type="";
			}
			if (slotid==null) {
				slotid="";
			}

			if (!type.equals(previousType)) {
				if (typeBuilder!=null) {
					slotBuilder.add("datecount",slotdatecount);
					slotBuilder.add("msgcount",slotmsgcount);
					slotBuilder.add("dates", datesBuilder.build());
					slotsBuilder.add(slotBuilder.build());
					slotdatecount=0;
					slotmsgcount=0;
					typeBuilder.add("slotcount",typeslotcount);
					typeBuilder.add("datecount",typedatecount);
					typeBuilder.add("msgcount",typemsgcount);
					typeBuilder.add("slots", slotsBuilder.build());
					types.add(typeBuilder.build());
					typeslotcount=0;
					typedatecount=0;
					typemsgcount=0;
					previousSlot=null;
					slotBuilder=null;
				}
				typeBuilder = Json.createObjectBuilder();
				typeBuilder.add("type", type);
				if (type.equalsIgnoreCase("E")) {
					typeBuilder.add("name","errorlog");
				} else {
					typeBuilder.add("name","messagelog");
				}
				slotsBuilder = Json.createArrayBuilder();
				previousType=type;
			}
			if (!slotid.equals(previousSlot)) {
				if (slotBuilder!=null) {
					slotBuilder.add("datecount",slotdatecount);
					slotBuilder.add("msgcount",slotmsgcount);
					slotBuilder.add("dates", datesBuilder.build());
					slotsBuilder.add(slotBuilder.build());
					slotdatecount=0;
					slotmsgcount=0;
				}
				slotBuilder=Json.createObjectBuilder();
				datesBuilder = Json.createArrayBuilder();
				slotBuilder.add("id",slotid);
				if (StringUtils.isNotEmpty(slotid)) {
					SlotIdRecord sir=slotmap.get(type+"/"+slotid);
					if (sir!=null) {
						slotBuilder.add("adapter",sir.adapterName);
						if (StringUtils.isNotEmpty(sir.receiverName) ) {
							slotBuilder.add("receiver",sir.receiverName);
						}
						if (StringUtils.isNotEmpty(sir.pipeName) ) {
							slotBuilder.add("pipe",sir.pipeName);
						}
					}
				}
				previousSlot=slotid;
				typeslotcount++;
			}
			typemsgcount+=count;
			typedatecount++;
			slotmsgcount+=count;
			slotdatecount++;

			datesBuilder.add(Json.createObjectBuilder().add("id",date).add("count",count).build());
		}

		if (slotBuilder!=null) {
			slotBuilder.add("datecount",slotdatecount);
			slotBuilder.add("msgcount",slotmsgcount);
			slotBuilder.add("dates", datesBuilder.build());
			slotsBuilder.add(slotBuilder.build());
		}

		if (typeBuilder!=null) {
			typeBuilder.add("slotcount",typeslotcount);
			typeBuilder.add("datecount",typedatecount);
			typeBuilder.add("msgcount",typemsgcount);
			typeBuilder.add("slots", slotsBuilder.build());
			types.add(typeBuilder.build());
		}

		JsonStructure result = types.build();
		return new PipeRunResult(null, new org.frankframework.stream.Message(result.toString()));
	}
}

class SlotIdRecord {
	public String adapterName;
	public String receiverName;
	public String pipeName;

	SlotIdRecord(String adapterName, String receiverName, String pipeName) {
		super();
		this.adapterName=adapterName;
		this.receiverName=receiverName;
		this.pipeName=pipeName;
	}
}

