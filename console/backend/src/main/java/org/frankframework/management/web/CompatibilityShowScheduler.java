/*
   Copyright 2023 WeAreFrank!

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
package org.frankframework.management.web;

import java.util.Map;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.cxf.jaxrs.ext.multipart.MultipartBody;

import org.frankframework.management.bus.BusAction;
import org.frankframework.management.bus.BusTopic;

import org.frankframework.util.RequestUtils;

/**
 * This class exists to provide backwards compatibility for endpoints from before #4069.
 *
 * @since	7.8.1
 * @author	Niels Meijer
 */
@Path("/")
public final class CompatibilityShowScheduler extends FrankApiBase {

	@GET
	@RolesAllowed({"IbisObserver", "IbisDataAdmin", "IbisAdmin", "IbisTester"})
	@Path("/schedules/{groupName}/job/{jobName}")
	@Relation("schedules")
	@Produces(MediaType.APPLICATION_JSON)
	@Deprecated
	public Response getScheduleOld(@PathParam("jobName") String jobName, @PathParam("groupName") String groupName) {
		RequestMessageBuilder builder = RequestMessageBuilder.create(this, BusTopic.SCHEDULER, BusAction.FIND);
		builder.addHeader("job", jobName);
		builder.addHeader("group", groupName);
		return callSyncGateway(builder);
	}

	@PUT
	@RolesAllowed({"IbisDataAdmin", "IbisAdmin", "IbisTester"})
	@Path("/schedules/{groupName}/job/{jobName}")
	@Relation("schedules")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Deprecated
	public Response triggerOld(@PathParam("jobName") String jobName, @PathParam("groupName") String groupName, Map<String, Object> json) {
		RequestMessageBuilder builder = RequestMessageBuilder.create(this, BusTopic.SCHEDULER, BusAction.MANAGE);
		builder.addHeader("operation", RequestUtils.getValue(json, "action"));
		builder.addHeader("job", jobName);
		builder.addHeader("group", groupName);
		return callSyncGateway(builder);
	}


	// Database jobs

	@PUT
	@RolesAllowed({"IbisDataAdmin", "IbisAdmin", "IbisTester"})
	@Path("/schedules/{groupName}/job/{jobName}")
	@Consumes(MediaType.MULTIPART_FORM_DATA)
	@Produces(MediaType.APPLICATION_JSON)
	@Deprecated
	public Response updateScheduleOld(@PathParam("groupName") String groupName, @PathParam("jobName") String jobName, MultipartBody input) {
		return ShowScheduler.createSchedule(this, groupName, jobName, input, true);
	}

	@POST
	@RolesAllowed({"IbisDataAdmin", "IbisAdmin", "IbisTester"})
	@Path("/schedules/{groupName}/job")
	@Relation("schedules")
	@Produces(MediaType.APPLICATION_JSON)
	@Deprecated
	public Response createScheduleInJobGroupOld(@PathParam("groupName") String groupName, MultipartBody input) {
		String jobName = RequestUtils.resolveStringFromMap(input, "name");
		return ShowScheduler.createSchedule(this, groupName, jobName, input, false);
	}

	@DELETE
	@RolesAllowed({"IbisDataAdmin", "IbisAdmin", "IbisTester"})
	@Path("/schedules/{groupName}/job/{jobName}")
	@Relation("schedules")
	@Produces(MediaType.APPLICATION_JSON)
	@Deprecated
	public Response deleteSchedulesOld(@PathParam("jobName") String jobName, @PathParam("groupName") String groupName) {
		RequestMessageBuilder builder = RequestMessageBuilder.create(this, BusTopic.SCHEDULER, BusAction.DELETE);
		builder.addHeader("job", jobName);
		builder.addHeader("group", groupName);
		return callSyncGateway(builder);
	}
}
