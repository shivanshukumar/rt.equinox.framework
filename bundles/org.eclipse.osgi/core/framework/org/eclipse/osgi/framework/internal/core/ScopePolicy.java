/*******************************************************************************
 * Copyright (c) 2009, 2010 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.osgi.framework.internal.core;

import org.eclipse.osgi.service.resolver.BaseDescription;
import org.eclipse.osgi.service.resolver.BundleDescription;
import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceReference;

/**
 * A scope policy determines the visibility of a resource provider to a 
 * potential client.
 * @noimplement This interface is not intended to be implemented by clients. 
 */
public interface ScopePolicy {
	/**
	 * Determines if the specified client should have visibility to the 
	 * specified service reference.
	 * @param client the potential client bundle
	 * @param serviceProvider the service reference to determine visibility of
	 * @param clazzes The class names under which the service is registered
	 * @return true if the client has visibility according to this scope policy; false otherwise
	 */
	boolean isVisible(Bundle client, ServiceReference<?> serviceProvider, String clazzes[]);

	/**
	 * Determines if the specified client should have visibility to the 
	 * specified constraint provider (exported package, bundle symbolic name etc.).
	 * @param client the potential client bundle
	 * @param constraintProvider the service reference to determine visibility of
	 * @return true if the client has visibility according to this scope policy; false otherwise
	 */
	boolean isVisible(BundleDescription client, BaseDescription constraintProvider);

	/**
	 * Determines if the specified client should have visibility to the 
	 * specified constraint provider (exported package, bundle symbolic name etc.).
	 * @param client the potential client bundle
	 * @param constraintProvider the service reference to determine visibility of
	 * @return true if the client has visibility according to this scope policy; false otherwise
	 */
	boolean isVisible(Bundle client, BaseDescription constraintProvider);

	/**
	 * Determines if the specified singleton bundle has a visibility policy
	 * that may allow an equivalent singleton to be visible from a parent 
	 * scope.  An equivalent singleton is one that has the same bundle 
	 * symbolic name as the specified singleton.
	 * @param singleton The singleton bundle to check.
	 * @return true if the singleton has a visibility policy that 
	 * may allow an equivalent singleton to be visible from a parent
	 * scope; false otherwise.
	 */
	boolean hasBundlePolicyEquivalent(BundleDescription singleton);

	/**
	 * Determines if the specified bundles are in the same scope.
	 * @param b1 a bundle
	 * @param b2 a bundle
	 * @return true if the two bundles are in the same scope.
	 */
	boolean sameScope(Bundle b1, Bundle b2);

	/**
	 * Determines if the specified descriptions are in the same scope.
	 * @param d1 a description
	 * @param d2 a description
	 * @return true if the two descriptions are in the same scope.
	 */
	boolean sameScope(BaseDescription d1, BaseDescription d2);

	/**
	 * Determines if the specified bundle represents a scope and returns
	 * all bundles contained within that scope.
	 * @return all bundles contained with in the specified bundle scope.
	 */
	//TODO need a better name
	BundleDescription[] getScopeContent(BundleDescription bundle);

	/**
	 * Returns true if there are no scopes defined by this scope policy.
	 * This method is useful to optimize cases where there are no scopes
	 * defined and therefore no reason to consult the scope policy for 
	 * visibility.
	 * @return true if one or more scopes are defined by this scope policy;
	 * otherwise false is returned.
	 */
	boolean noScopes();
}
