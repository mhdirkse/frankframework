/*
   Copyright 2013 Nationale-Nederlanden, 2021 WeAreFrank!

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

package org.frankframework.monitoring;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.DisposableBean;

import org.frankframework.configuration.ConfigurationException;
import org.frankframework.doc.FrankDocGroup;
import org.frankframework.lifecycle.LazyLoadingEventListener;
import org.frankframework.monitoring.events.FireMonitorEvent;
import org.frankframework.util.XmlBuilder;

@FrankDocGroup(name = "Monitoring", order = 80)
public interface ITrigger extends LazyLoadingEventListener<FireMonitorEvent>, DisposableBean {
	public enum TriggerType {
		ALARM,
		CLEARING
	}

	boolean isAlarm();
	void clearEvents();
	void configure() throws ConfigurationException;
	boolean isConfigured();
	void setMonitor(Monitor monitor);
	void toXml(XmlBuilder monitor);

	void setSourceFiltering(SourceFiltering filtering);
	SourceFiltering getSourceFiltering();

	void setEventCodes(List<String> events);
	List<String> getEventCodes();

	void setSeverity(Severity severity);
	Severity getSeverity();

	void setThreshold(int i);
	int getThreshold();

	void setPeriod(int i);
	int getPeriod();

	void registerAdapterFilter(AdapterFilter af);
	Map<String, AdapterFilter> getAdapterFilters();
	void clearAdapterFilters();

	void setTriggerType(TriggerType type);
	TriggerType getTriggerType();
}
