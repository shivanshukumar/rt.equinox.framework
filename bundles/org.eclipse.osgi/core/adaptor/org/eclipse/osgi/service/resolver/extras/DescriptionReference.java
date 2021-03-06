/*******************************************************************************
 * Copyright (c) 2011 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.osgi.service.resolver.extras;

import org.eclipse.osgi.service.resolver.BaseDescription;

/**
 * A reference to a {@link BaseDescription}.
 * @since 3.8
 */
public interface DescriptionReference {
	/**
	 * Returns the {@code BaseDescription} object associated with this
	 * reference.
	 * 
	 * @return The {@code BaseDescription} object associated with this
	 *         reference.
	 */
	public BaseDescription getDescription();
}
