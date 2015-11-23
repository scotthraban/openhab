/**
 * Copyright (c) 2010-2013, openHAB.org and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.action.gcaltemp.internal;

import java.util.Map;

import org.openhab.core.scriptengine.action.ActionService;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
	

/**
 * This class registers an OSGi service for the GCalTemp action.
 * 
 * @author Scott Hraban
 * @since 1.0-SNAPSHOT
 */
public class GCalTempActionService implements ActionService {

	private static final Logger logger = LoggerFactory.getLogger(GCalTempActionService.class);

	/**
	 * The BundleContext. This is only valid when the bundle is ACTIVE. It is set in the activate()
	 * method and must not be accessed anymore once the deactivate() method was called or before activate()
	 * was called.
	 */
	private BundleContext bundleContext;
	
	/**
	 * Indicates whether this action is properly configured which means all
	 * necessary configurations are set. This flag can be checked by the
	 * action methods before executing code.
	 */
	/* default */ static boolean isProperlyConfigured = false;
	
	public GCalTempActionService() {
	}
	
	/**
	 * Called by the SCR to activate the component with its configuration read from CAS
	 * 
	 * @param bundleContext BundleContext of the Bundle that defines this component
	 * @param configuration Configuration properties for this component obtained from the ConfigAdmin service
	 */
	public void activate(final BundleContext bundleContext, final Map<String, Object> configuration) {
		this.bundleContext = bundleContext;

		// the configuration is guaranteed not to be null, because the component definition has the
		// configuration-policy set to require. If set to 'optional' then the configuration may be null
		
		try {
			GCalTemp.getInstance().configure((String) configuration.get("clientId"),
					(String) configuration.get("clientSecret"),
					(String) configuration.get("calendarName"),
					(String) configuration.get("calendarGranularity"),
					(String) configuration.get("calendarLookahead"));
		} catch (Exception e) {
			logger.error("Unable to configure GCalTemp Action", e);
			return;
		}
		
		isProperlyConfigured = true;
	}
	
	/**
	 * Called by the SCR when the configuration of a binding has been changed through the ConfigAdmin service.
	 * @param configuration Updated configuration properties
	 */
	public void modified(final Map<String, Object> configuration) {
		try {
			GCalTemp.getInstance().configure((String) configuration.get("clientId"),
					(String) configuration.get("clientSecret"),
					(String) configuration.get("calendarName"),
					(String) configuration.get("calendarGranularity"),
					(String) configuration.get("calendarLookahead"));
		} catch (Exception e) {
			logger.error("Unable to configure GCalTemp Action", e);
			isProperlyConfigured = false;
		}
	}
	
	/**
	 * Called by the SCR to deactivate the component when either the configuration is removed or
	 * mandatory references are no longer satisfied or the component has simply been stopped.
	 * @param reason Reason code for the deactivation:<br>
	 * <ul>
	 * <li> 0 – Unspecified
     * <li> 1 – The component was disabled
     * <li> 2 – A reference became unsatisfied
     * <li> 3 – A configuration was changed
     * <li> 4 – A configuration was deleted
     * <li> 5 – The component was disposed
     * <li> 6 – The bundle was stopped
     * </ul>
	 */
	public void deactivate(final int reason) {
		this.bundleContext = null;
		// deallocate resources here that are no longer needed and 
		// should be reset when activating this binding again
	}
	
	@Override
	public String getActionClassName() {
		return GCalTemp.class.getCanonicalName();
	}

	@Override
	public Class<?> getActionClass() {
		return GCalTemp.class;
	}
	
}
