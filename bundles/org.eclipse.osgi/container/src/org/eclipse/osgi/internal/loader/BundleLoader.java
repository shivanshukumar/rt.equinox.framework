/*******************************************************************************
 * Copyright (c) 2004, 2011 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.osgi.internal.loader;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.osgi.container.*;
import org.eclipse.osgi.container.builders.OSGiManifestBuilderFactory;
import org.eclipse.osgi.container.namespaces.EquinoxModuleDataNamespace;
import org.eclipse.osgi.framework.util.KeyedElement;
import org.eclipse.osgi.framework.util.KeyedHashSet;
import org.eclipse.osgi.internal.debug.Debug;
import org.eclipse.osgi.internal.framework.EquinoxContainer;
import org.eclipse.osgi.internal.hookregistry.ClassLoaderHook;
import org.eclipse.osgi.internal.loader.buddy.PolicyHandler;
import org.eclipse.osgi.internal.loader.sources.*;
import org.eclipse.osgi.storage.BundleInfo.Generation;
import org.eclipse.osgi.util.ManifestElement;
import org.osgi.framework.*;
import org.osgi.framework.namespace.BundleNamespace;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.service.resolver.ResolutionException;

/**
 * This object is responsible for all classloader delegation for a bundle.
 * It represents the loaded state of the bundle.  BundleLoader objects
 * are created lazily; care should be taken not to force the creation
 * of a BundleLoader unless it is necessary.
 * @see org.eclipse.osgi.internal.loader.BundleLoaderSources
 */
public class BundleLoader implements ModuleLoader {
	public final static String DEFAULT_PACKAGE = "."; //$NON-NLS-1$
	public final static String JAVA_PACKAGE = "java."; //$NON-NLS-1$

	public final static ClassContext CLASS_CONTEXT = AccessController.doPrivileged(new PrivilegedAction<ClassContext>() {
		public ClassContext run() {
			return new ClassContext();
		}
	});
	public final static ClassLoader FW_CLASSLOADER = getClassLoader(EquinoxContainer.class);

	private static final int PRE_CLASS = 1;
	private static final int POST_CLASS = 2;
	private static final int PRE_RESOURCE = 3;
	private static final int POST_RESOURCE = 4;
	private static final int PRE_RESOURCES = 5;
	private static final int POST_RESOURCES = 6;

	private static final Pattern PACKAGENAME_FILTER = Pattern.compile("\\(osgi.wiring.package\\s*=\\s*([^)]+)\\)"); //$NON-NLS-1$

	private final ModuleWiring wiring;
	private final EquinoxContainer container;
	private final Debug debug;
	private final PolicyHandler policy;

	/* List of package names that are exported by this BundleLoader */
	private final Collection<String> exportedPackages;
	private final BundleLoaderSources exportSources;

	/* cache of required package sources. Key is packagename, value is PackageSource */
	private final KeyedHashSet requiredSources = new KeyedHashSet(false);
	/* cache of imported packages. Key is packagename, Value is PackageSource */
	private final KeyedHashSet importedSources = new KeyedHashSet(false);

	/* @GuardedBy("importedSources") */
	private boolean importsInitialized = false;
	/* @GuardedBy("importedSources") */
	private boolean dynamicAllPackages;
	/* If not null, list of package stems to import dynamically. */
	/* @GuardedBy("importedSources") */
	private String[] dynamicImportPackageStems;
	/* @GuardedBy("importedSources") */
	/* If not null, list of package names to import dynamically. */
	private String[] dynamicImportPackages;

	private Object classLoaderMonitor = new Object();
	/* @GuardedBy("classLoaderMonitor") */
	private ModuleClassLoader classloader;
	private final ClassLoader parent;

	/**
	 * Returns the package name from the specified class name.
	 * The returned package is dot seperated.
	 *
	 * @param name   Name of a class.
	 * @return Dot separated package name or null if the class
	 *         has no package name.
	 */
	public final static String getPackageName(String name) {
		if (name != null) {
			int index = name.lastIndexOf('.'); /* find last period in class name */
			if (index > 0)
				return name.substring(0, index);
		}
		return DEFAULT_PACKAGE;
	}

	/**
	 * Returns the package name from the specified resource name.
	 * The returned package is dot seperated.
	 *
	 * @param name   Name of a resource.
	 * @return Dot separated package name or null if the resource
	 *         has no package name.
	 */
	public final static String getResourcePackageName(String name) {
		if (name != null) {
			/* check for leading slash*/
			int begin = ((name.length() > 1) && (name.charAt(0) == '/')) ? 1 : 0;
			int end = name.lastIndexOf('/'); /* index of last slash */
			if (end > begin)
				return name.substring(begin, end).replace('/', '.');
		}
		return DEFAULT_PACKAGE;
	}

	public BundleLoader(ModuleWiring wiring, EquinoxContainer container, ClassLoader parent) {
		this.wiring = wiring;
		this.container = container;
		this.debug = container.getConfiguration().getDebug();
		this.parent = parent;

		// init the provided packages set
		exportSources = new BundleLoaderSources(this);
		List<ModuleCapability> exports = wiring.getModuleCapabilities(PackageNamespace.PACKAGE_NAMESPACE);
		exportedPackages = Collections.synchronizedCollection(exports.size() > 10 ? new HashSet<String>(exports.size()) : new ArrayList<String>(exports.size()));
		initializeExports(exports, exportSources, exportedPackages);

		// init the dynamic imports tables
		addDynamicImportPackage(wiring.getModuleRequirements(PackageNamespace.PACKAGE_NAMESPACE));

		//Initialize the policy handler
		List<ModuleCapability> moduleDatas = wiring.getRevision().getModuleCapabilities(EquinoxModuleDataNamespace.MODULE_DATA_NAMESPACE);
		@SuppressWarnings("unchecked")
		List<String> buddyList = (List<String>) (moduleDatas.isEmpty() ? null : moduleDatas.get(0).getAttributes().get(EquinoxModuleDataNamespace.CAPABILITY_BUDDY_POLICY));
		policy = buddyList != null ? new PolicyHandler(this, buddyList, container.getPackageAdmin()) : null;
		if (policy != null) {
			Module systemModule = container.getStorage().getModuleContainer().getModule(0);
			Bundle systemBundle = systemModule.getBundle();
			policy.open(systemBundle.getBundleContext());
		}
	}

	public ModuleWiring getWiring() {
		return wiring;
	}

	private static void initializeExports(List<ModuleCapability> exports, BundleLoaderSources sources, Collection<String> exportNames) {
		for (ModuleCapability export : exports) {
			String name = (String) export.getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE);
			if (sources.forceSourceCreation(export)) {
				if (!exportNames.contains(name)) {
					// must force filtered and reexport sources to be created early
					// to prevent lazy normal package source creation.
					// We only do this for the first export of a package name. 
					sources.createPackageSource(export, true);
				}
			}
			exportNames.add(name);
		}
	}

	final PackageSource createExportPackageSource(ModuleWire importWire, Collection<BundleLoader> visited) {
		BundleLoader providerLoader = (BundleLoader) importWire.getProviderWiring().getModuleLoader();
		String name = (String) importWire.getCapability().getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE);
		PackageSource requiredSource = providerLoader.findRequiredSource(name, visited);
		PackageSource exportSource = providerLoader.exportSources.createPackageSource(importWire.getCapability(), false);
		if (requiredSource == null)
			return exportSource;
		return createMultiSource(name, new PackageSource[] {requiredSource, exportSource});
	}

	private static PackageSource createMultiSource(String packageName, PackageSource[] sources) {
		if (sources.length == 1)
			return sources[0];
		List<SingleSourcePackage> sourceList = new ArrayList<SingleSourcePackage>(sources.length);
		for (int i = 0; i < sources.length; i++) {
			SingleSourcePackage[] innerSources = sources[i].getSuppliers();
			for (int j = 0; j < innerSources.length; j++)
				if (!sourceList.contains(innerSources[j]))
					sourceList.add(innerSources[j]);
		}
		return new MultiSourcePackage(packageName, sourceList.toArray(new SingleSourcePackage[sourceList.size()]));
	}

	public ModuleClassLoader getModuleClassLoader() {
		synchronized (classLoaderMonitor) {
			if (classloader == null) {
				final Generation generation = (Generation) wiring.getRevision().getRevisionInfo();
				if (System.getSecurityManager() == null) {
					classloader = new ModuleClassLoader(parent, generation.getBundleInfo().getStorage().getConfiguration(), this, generation);
				} else {
					classloader = AccessController.doPrivileged(new PrivilegedAction<ModuleClassLoader>() {
						@Override
						public ModuleClassLoader run() {
							return new ModuleClassLoader(parent, generation.getBundleInfo().getStorage().getConfiguration(), BundleLoader.this, generation);
						}
					});
				}
			}
			return classloader;
		}
	}

	public void close() {
		synchronized (classLoaderMonitor) {
			if (classloader != null) {
				classloader.getClasspathManager().close();
				classloader = null;
			}
		}
	}

	@Override
	public ClassLoader getClassLoader() {
		return getModuleClassLoader();
	}

	public ClassLoader getParentClassLoader() {
		return this.parent;
	}

	/**
	 * This method loads a class from the bundle.  The class is searched for in the
	 * same manner as it would if it was being loaded from a bundle (i.e. all
	 * hosts, fragments, import, required bundles and local resources are searched.
	 *
	 * @param      name     the name of the desired Class.
	 * @return     the resulting Class
	 * @exception  java.lang.ClassNotFoundException  if the class definition was not found.
	 */
	final public Class<?> loadClass(String name) throws ClassNotFoundException {
		ModuleClassLoader mcl = getModuleClassLoader();
		// The instanceof check here is just to be safe.  The javadoc contract stated in BundleClassLoader
		// mandate that BundleClassLoaders be an instance of ClassLoader.
		if (name.length() > 0 && name.charAt(0) == '[')
			return Class.forName(name, false, mcl);
		return mcl.loadClass(name);
	}

	/**
	 * This method gets a resource from the bundle.  The resource is searched 
	 * for in the same manner as it would if it was being loaded from a bundle 
	 * (i.e. all hosts, fragments, import, required bundles and 
	 * local resources are searched).
	 *
	 * @param name the name of the desired resource.
	 * @return the resulting resource URL or null if it does not exist.
	 */
	final URL getResource(String name) {
		return getModuleClassLoader().getResource(name);
	}

	/**
	 * Finds a class local to this bundle.  Only the classloader for this bundle is searched.
	 * @param name The name of the class to find.
	 * @return The loaded Class or null if the class is not found.
	 * @throws ClassNotFoundException 
	 */
	public Class<?> findLocalClass(String name) throws ClassNotFoundException {
		if (debug.DEBUG_LOADER)
			Debug.println("BundleLoader[" + this + "].findLocalClass(" + name + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		try {
			Class<?> clazz = getModuleClassLoader().findLocalClass(name);
			if (debug.DEBUG_LOADER && clazz != null)
				Debug.println("BundleLoader[" + this + "] found local class " + name); //$NON-NLS-1$ //$NON-NLS-2$
			return clazz;
		} catch (ClassNotFoundException e) {
			if (e.getCause() instanceof BundleException) {
				// Here we assume this is because of a lazy activation error
				throw e;
			}
			return null;
		}
	}

	/**
	 * Finds the class for a bundle.  This method is used for delegation by the bundle's classloader.
	 */
	public Class<?> findClass(String name) throws ClassNotFoundException {
		return findClass(name, true);
	}

	Class<?> findClass(String name, boolean checkParent) throws ClassNotFoundException {
		if (checkParent && parent != null && name.startsWith(JAVA_PACKAGE))
			// 1) if startsWith "java." delegate to parent and terminate search
			// we want to throw ClassNotFoundExceptions if a java.* class cannot be loaded from the parent.
			return parent.loadClass(name);
		return findClassInternal(name, checkParent);
	}

	private Class<?> findClassInternal(String name, boolean checkParent) throws ClassNotFoundException {
		if (debug.DEBUG_LOADER)
			Debug.println("BundleLoader[" + this + "].loadBundleClass(" + name + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		String pkgName = getPackageName(name);
		boolean bootDelegation = false;
		// follow the OSGi delegation model
		if (checkParent && parent != null && container.isBootDelegationPackage(pkgName))
			// 2) if part of the bootdelegation list then delegate to parent and continue of failure
			try {
				return parent.loadClass(name);
			} catch (ClassNotFoundException cnfe) {
				// we want to continue
				bootDelegation = true;
			}
		Class<?> result = null;
		try {
			result = (Class<?>) searchHooks(name, PRE_CLASS);
		} catch (ClassNotFoundException e) {
			throw e;
		} catch (FileNotFoundException e) {
			// will not happen
		}
		if (result != null)
			return result;
		// 3) search the imported packages
		PackageSource source = findImportedSource(pkgName, null);
		if (source != null) {
			// 3) found import source terminate search at the source
			result = source.loadClass(name);
			if (result != null)
				return result;
			throw new ClassNotFoundException(name);
		}
		// 4) search the required bundles
		source = findRequiredSource(pkgName, null);
		if (source != null)
			// 4) attempt to load from source but continue on failure
			result = source.loadClass(name);
		// 5) search the local bundle
		if (result == null)
			result = findLocalClass(name);
		if (result != null)
			return result;
		// 6) attempt to find a dynamic import source; only do this if a required source was not found
		if (source == null) {
			source = findDynamicSource(pkgName);
			if (source != null) {
				result = source.loadClass(name);
				if (result != null)
					return result;
				// must throw CNFE if dynamic import source does not have the class
				throw new ClassNotFoundException(name);
			}
		}

		if (result == null)
			try {
				result = (Class<?>) searchHooks(name, POST_CLASS);
			} catch (ClassNotFoundException e) {
				throw e;
			} catch (FileNotFoundException e) {
				// will not happen
			}
		// do buddy policy loading
		if (result == null && policy != null)
			result = policy.doBuddyClassLoading(name);
		if (result != null)
			return result;
		// hack to support backwards compatibiility for bootdelegation
		// or last resort; do class context trick to work around VM bugs
		if (parent != null && !bootDelegation && ((checkParent && container.getConfiguration().compatibiltyBootDelegation) || isRequestFromVM()))
			// we don't need to continue if a CNFE is thrown here.
			try {
				return parent.loadClass(name);
			} catch (ClassNotFoundException e) {
				// we want to generate our own exception below
			}
		throw new ClassNotFoundException(name);
	}

	@SuppressWarnings("unchecked")
	private <E> E searchHooks(String name, int type) throws ClassNotFoundException, FileNotFoundException {

		List<ClassLoaderHook> loaderHooks = container.getConfiguration().getHookRegistry().getClassLoaderHooks();
		if (loaderHooks == null)
			return null;
		E result = null;
		for (ClassLoaderHook hook : loaderHooks) {
			switch (type) {
				case PRE_CLASS :
					result = (E) hook.preFindClass(name, getModuleClassLoader());
					break;
				case POST_CLASS :
					result = (E) hook.postFindClass(name, getModuleClassLoader());
					break;
				case PRE_RESOURCE :
					result = (E) hook.preFindResource(name, getModuleClassLoader());
					break;
				case POST_RESOURCE :
					result = (E) hook.postFindResource(name, getModuleClassLoader());
					break;
				case PRE_RESOURCES :
					result = (E) hook.preFindResources(name, getModuleClassLoader());
					break;
				case POST_RESOURCES :
					result = (E) hook.postFindResources(name, getModuleClassLoader());
					break;
			}
		}
		return result;
	}

	private boolean isRequestFromVM() {
		if (!container.getConfiguration().contextBootDelegation)
			return false;
		// works around VM bugs that require all classloaders to have access to parent packages
		Class<?>[] context = CLASS_CONTEXT.getClassContext();
		if (context == null || context.length < 2)
			return false;
		// skip the first class; it is the ClassContext class
		for (int i = 1; i < context.length; i++)
			// find the first class in the context which is not BundleLoader or instanceof ClassLoader
			if (context[i] != BundleLoader.class && !ClassLoader.class.isAssignableFrom(context[i])) {
				// only find in parent if the class is not "Class" (Class#forName case) or if the class is not loaded with a BundleClassLoader
				ClassLoader cl = getClassLoader(context[i]);
				if (cl != FW_CLASSLOADER) { // extra check incase an adaptor adds another class into the stack besides an instance of ClassLoader
					if (Class.class != context[i] && !(cl instanceof ModuleClassLoader))
						return true;
					break;
				}
			}
		return false;
	}

	private static ClassLoader getClassLoader(final Class<?> clazz) {
		if (System.getSecurityManager() == null)
			return clazz.getClassLoader();
		return AccessController.doPrivileged(new PrivilegedAction<ClassLoader>() {
			public ClassLoader run() {
				return clazz.getClassLoader();
			}
		});
	}

	/**
	 * Finds the resource for a bundle.  This method is used for delegation by the bundle's classloader.
	 */
	public URL findResource(String name) {
		return findResource(name, true);
	}

	URL findResource(String name, boolean checkParent) {
		if ((name.length() > 1) && (name.charAt(0) == '/')) /* if name has a leading slash */
			name = name.substring(1); /* remove leading slash before search */
		String pkgName = getResourcePackageName(name);
		boolean bootDelegation = false;
		// follow the OSGi delegation model
		// First check the parent classloader for system resources, if it is a java resource.
		if (checkParent && parent != null) {
			if (pkgName.startsWith(JAVA_PACKAGE))
				// 1) if startsWith "java." delegate to parent and terminate search
				// we never delegate java resource requests past the parent
				return parent.getResource(name);
			else if (container.isBootDelegationPackage(pkgName)) {
				// 2) if part of the bootdelegation list then delegate to parent and continue of failure
				URL result = parent.getResource(name);
				if (result != null)
					return result;
				bootDelegation = true;
			}
		}

		URL result = null;
		try {
			result = (URL) searchHooks(name, PRE_RESOURCE);
		} catch (FileNotFoundException e) {
			return null;
		} catch (ClassNotFoundException e) {
			// will not happen
		}
		if (result != null)
			return result;
		// 3) search the imported packages
		PackageSource source = findImportedSource(pkgName, null);
		if (source != null)
			// 3) found import source terminate search at the source
			return source.getResource(name);
		// 4) search the required bundles
		source = findRequiredSource(pkgName, null);
		if (source != null)
			// 4) attempt to load from source but continue on failure
			result = source.getResource(name);
		// 5) search the local bundle
		if (result == null)
			result = findLocalResource(name);
		if (result != null)
			return result;
		// 6) attempt to find a dynamic import source; only do this if a required source was not found
		if (source == null) {
			source = findDynamicSource(pkgName);
			if (source != null)
				// must return the result of the dynamic import and do not continue
				return source.getResource(name);
		}

		if (result == null)
			try {
				result = (URL) searchHooks(name, POST_RESOURCE);
			} catch (FileNotFoundException e) {
				return null;
			} catch (ClassNotFoundException e) {
				// will not happen
			}
		// do buddy policy loading
		if (result == null && policy != null)
			result = policy.doBuddyResourceLoading(name);
		if (result != null)
			return result;
		// hack to support backwards compatibiility for bootdelegation
		// or last resort; do class context trick to work around VM bugs
		if (parent != null && !bootDelegation && ((checkParent && container.getConfiguration().compatibiltyBootDelegation) || isRequestFromVM()))
			// we don't need to continue if the resource is not found here
			return parent.getResource(name);
		return result;
	}

	/**
	 * Finds the resources for a bundle.  This  method is used for delegation by the bundle's classloader.
	 */
	public Enumeration<URL> findResources(String name) throws IOException {
		// do not delegate to parent because ClassLoader#getResources already did and it is final!!
		if ((name.length() > 1) && (name.charAt(0) == '/')) /* if name has a leading slash */
			name = name.substring(1); /* remove leading slash before search */
		String pkgName = getResourcePackageName(name);
		Enumeration<URL> result = null;
		try {
			result = searchHooks(name, PRE_RESOURCES);
		} catch (ClassNotFoundException e) {
			// will not happen
		} catch (FileNotFoundException e) {
			return null;
		}
		if (result != null)
			return result;
		// start at step 3 because of the comment above about ClassLoader#getResources
		// 3) search the imported packages
		PackageSource source = findImportedSource(pkgName, null);
		if (source != null)
			// 3) found import source terminate search at the source
			return source.getResources(name);
		// 4) search the required bundles
		source = findRequiredSource(pkgName, null);
		if (source != null)
			// 4) attempt to load from source but continue on failure
			result = source.getResources(name);

		// 5) search the local bundle
		// compound the required source results with the local ones
		Enumeration<URL> localResults = findLocalResources(name);
		result = compoundEnumerations(result, localResults);
		// 6) attempt to find a dynamic import source; only do this if a required source was not found
		if (result == null && source == null) {
			source = findDynamicSource(pkgName);
			if (source != null)
				return source.getResources(name);
		}
		if (result == null)
			try {
				result = searchHooks(name, POST_RESOURCES);
			} catch (ClassNotFoundException e) {
				// will not happen
			} catch (FileNotFoundException e) {
				return null;
			}
		if (policy != null) {
			Enumeration<URL> buddyResult = policy.doBuddyResourcesLoading(name);
			result = compoundEnumerations(result, buddyResult);
		}
		return result;
	}

	private boolean isSubPackage(String parentPackage, String subPackage) {
		String prefix = (parentPackage.length() == 0 || parentPackage.equals(DEFAULT_PACKAGE)) ? "" : parentPackage + '.'; //$NON-NLS-1$
		return subPackage.startsWith(prefix);
	}

	@Override
	public Collection<String> listResources(String path, String filePattern, int options) {
		String pkgName = getResourcePackageName(path.endsWith("/") ? path : path + '/'); //$NON-NLS-1$
		if ((path.length() > 1) && (path.charAt(0) == '/')) /* if name has a leading slash */
			path = path.substring(1); /* remove leading slash before search */
		boolean subPackages = (options & BundleWiring.LISTRESOURCES_RECURSE) != 0;
		List<String> packages = new ArrayList<String>();
		// search imported package names
		KeyedHashSet importSources = getImportedSources(null);
		if (importSources != null) {
			KeyedElement[] imports = importSources.elements();
			for (KeyedElement keyedElement : imports) {
				String id = ((PackageSource) keyedElement).getId();
				if (id.equals(pkgName) || (subPackages && isSubPackage(pkgName, id)))
					packages.add(id);
			}
		}

		// now add package names from required bundles
		Collection<BundleLoader> visited = new ArrayList<BundleLoader>();
		visited.add(this); // always add ourselves so we do not recurse back to ourselves
		List<ModuleWire> requireBundles = wiring.getRequiredModuleWires(BundleNamespace.BUNDLE_NAMESPACE);
		for (ModuleWire bundleWire : requireBundles) {
			((BundleLoader) bundleWire.getProviderWiring().getModuleLoader()).addProvidedPackageNames(pkgName, packages, subPackages, visited);
		}

		boolean localSearch = (options & BundleWiring.LISTRESOURCES_LOCAL) != 0;
		List<String> result = new ArrayList<String>();
		Set<String> importedPackages = new HashSet<String>(0);
		for (String name : packages) {
			// look for import source
			PackageSource externalSource = findImportedSource(name, null);
			if (externalSource != null) {
				// record this package is imported
				importedPackages.add(name);
			} else {
				// look for require bundle source
				externalSource = findRequiredSource(name, null);
			}
			// only add the content of the external source if this is not a localSearch
			if (externalSource != null && !localSearch) {
				String packagePath = name.replace('.', '/');
				Collection<String> externalResources = externalSource.listResources(packagePath, filePattern);
				for (String resource : externalResources) {
					if (!result.contains(resource)) // prevent duplicates; could happen if the package is split or exporter has fragments/multiple jars
						result.add(resource);
				}
			}
		}

		// now search locally
		Collection<String> localResources = getModuleClassLoader().listLocalResources(path, filePattern, options);
		for (String resource : localResources) {
			String resourcePkg = getResourcePackageName(resource);
			if (!importedPackages.contains(resourcePkg) && !result.contains(resource))
				result.add(resource);
		}
		return result;
	}

	@Override
	public List<URL> findEntries(String path, String filePattern, int options) {
		return getModuleClassLoader().findEntries(path, filePattern, options);
	}

	/*
	 * This method is used by Bundle.getResources to do proper parent delegation.
	 */
	public Enumeration<URL> getResources(String name) throws IOException {
		if ((name.length() > 1) && (name.charAt(0) == '/')) /* if name has a leading slash */
			name = name.substring(1); /* remove leading slash before search */
		String pkgName = getResourcePackageName(name);
		// follow the OSGi delegation model
		// First check the parent classloader for system resources, if it is a java resource.
		Enumeration<URL> result = null;
		if (pkgName.startsWith(JAVA_PACKAGE) || container.isBootDelegationPackage(pkgName)) {
			// 1) if startsWith "java." delegate to parent and terminate search
			// 2) if part of the bootdelegation list then delegate to parent and continue of failure
			result = parent == null ? null : parent.getResources(name);
			if (pkgName.startsWith(JAVA_PACKAGE))
				return result;
		}
		return compoundEnumerations(result, findResources(name));
	}

	public static <E> Enumeration<E> compoundEnumerations(Enumeration<E> list1, Enumeration<E> list2) {
		if (list2 == null || !list2.hasMoreElements())
			return list1;
		if (list1 == null || !list1.hasMoreElements())
			return list2;
		List<E> compoundResults = new ArrayList<E>();
		while (list1.hasMoreElements())
			compoundResults.add(list1.nextElement());
		while (list2.hasMoreElements()) {
			E item = list2.nextElement();
			if (!compoundResults.contains(item)) //don't add duplicates
				compoundResults.add(item);
		}
		return Collections.enumeration(compoundResults);
	}

	/**
	 * Finds a resource local to this bundle.  Only the classloader for this bundle is searched.
	 * @param name The name of the resource to find.
	 * @return The URL to the resource or null if the resource is not found.
	 */
	public URL findLocalResource(final String name) {
		return getModuleClassLoader().findLocalResource(name);
	}

	/**
	 * Returns an Enumeration of URLs representing all the resources with
	 * the given name. Only the classloader for this bundle is searched.
	 *
	 * @param  name the resource name
	 * @return an Enumeration of URLs for the resources
	 */
	public Enumeration<URL> findLocalResources(String name) {
		return getModuleClassLoader().findLocalResources(name);
	}

	/**
	 * Return a string representation of this loader.
	 * @return String
	 */
	public final String toString() {
		return wiring.getRevision().toString();
	}

	/**
	 * Return true if the target package name matches
	 * a name in the DynamicImport-Package manifest header.
	 *
	 * @param pkgname The name of the requested class' package.
	 * @return true if the package should be imported.
	 */
	private final boolean isDynamicallyImported(String pkgname) {
		if (this instanceof SystemBundleLoader)
			return false; // system bundle cannot dynamically import
		// must check for startsWith("java.") to satisfy R3 section 4.7.2
		if (pkgname.startsWith("java.")) //$NON-NLS-1$
			return true;

		synchronized (importedSources) {
			/* "*" shortcut */
			if (dynamicAllPackages)
				return true;

			/* match against specific names */
			if (dynamicImportPackages != null)
				for (int i = 0; i < dynamicImportPackages.length; i++)
					if (pkgname.equals(dynamicImportPackages[i]))
						return true;

			/* match against names with trailing wildcards */
			if (dynamicImportPackageStems != null)
				for (int i = 0; i < dynamicImportPackageStems.length; i++)
					if (pkgname.startsWith(dynamicImportPackageStems[i]))
						return true;
		}
		return false;
	}

	final void addExportedProvidersFor(String packageName, List<PackageSource> result, Collection<BundleLoader> visited) {
		if (visited.contains(this))
			return;
		visited.add(this);
		// See if we locally provide the package.
		PackageSource local = null;
		if (isExportedPackage(packageName))
			local = exportSources.getPackageSource(packageName);
		else if (isSubstitutedExport(packageName)) {
			result.add(findImportedSource(packageName, visited));
			return; // should not continue to required bundles in this case
		}
		// Must search required bundles that are exported first.
		List<ModuleWire> requireBundles = wiring.getRequiredModuleWires(BundleNamespace.BUNDLE_NAMESPACE);
		for (ModuleWire bundleWire : requireBundles) {
			if (local != null || BundleNamespace.VISIBILITY_REEXPORT.equals(bundleWire.getRequirement().getDirectives().get(BundleNamespace.REQUIREMENT_VISIBILITY_DIRECTIVE))) {
				// always add required bundles first if we locally provide the package
				// This allows a bundle to provide a package from a required bundle without 
				// re-exporting the whole required bundle.
				((BundleLoader) bundleWire.getProviderWiring().getModuleLoader()).addExportedProvidersFor(packageName, result, visited);
			}
		}
		// now add the locally provided package.
		if (local != null)
			result.add(local);
	}

	final void addProvidedPackageNames(String packageName, List<String> result, boolean subPackages, Collection<BundleLoader> visited) {
		if (visited.contains(this))
			return;
		visited.add(this);
		for (String exported : exportedPackages) {
			if (exported.equals(packageName) || (subPackages && isSubPackage(packageName, exported))) {
				if (!result.contains(exported))
					result.add(exported);
			}
		}

		for (String substituted : wiring.getSubstitutedNames()) {
			if (substituted.equals(packageName) || (subPackages && isSubPackage(packageName, substituted))) {
				if (!result.contains(substituted))
					result.add(substituted);
			}
		}
		List<ModuleWire> requireBundles = wiring.getRequiredModuleWires(BundleNamespace.BUNDLE_NAMESPACE);
		for (ModuleWire bundleWire : requireBundles) {
			if (BundleNamespace.VISIBILITY_REEXPORT.equals(bundleWire.getRequirement().getDirectives().get(BundleNamespace.REQUIREMENT_VISIBILITY_DIRECTIVE))) {
				((BundleLoader) bundleWire.getProviderWiring().getModuleLoader()).addProvidedPackageNames(packageName, result, subPackages, visited);
			}
		}
	}

	public final boolean isExportedPackage(String name) {
		return exportedPackages.contains(name);
	}

	final boolean isSubstitutedExport(String name) {
		return wiring.isSubstitutedPackage(name);
	}

	private void addDynamicImportPackage(List<ModuleRequirement> packageImports) {
		if (packageImports.isEmpty()) {
			return;
		}
		List<String> dynamicImports = new ArrayList<String>(packageImports.size());
		for (ModuleRequirement packageImport : packageImports) {
			if (PackageNamespace.RESOLUTION_DYNAMIC.equals(packageImport.getDirectives().get(PackageNamespace.REQUIREMENT_RESOLUTION_DIRECTIVE))) {
				Matcher matcher = PACKAGENAME_FILTER.matcher(packageImport.getDirectives().get(PackageNamespace.REQUIREMENT_FILTER_DIRECTIVE));
				if (matcher.find()) {
					String dynamicName = matcher.group(1);
					if (dynamicName != null) {
						dynamicImports.add(dynamicName);
					}
				}
			}
		}
		if (dynamicImports.size() > 0)
			addDynamicImportPackage(dynamicImports.toArray(new String[dynamicImports.size()]));
	}

	/**
	 * Adds a list of DynamicImport-Package manifest elements to the dynamic
	 * import tables of this BundleLoader.  Duplicate packages are checked and
	 * not added again.  This method is not thread safe.  Callers should ensure
	 * synchronization when calling this method.
	 * @param packages the DynamicImport-Package elements to add.
	 */
	private void addDynamicImportPackage(String[] packages) {
		if (packages == null)
			return;

		synchronized (importedSources) {
			int size = packages.length;
			List<String> stems;
			if (dynamicImportPackageStems == null) {
				stems = new ArrayList<String>(size);
			} else {
				stems = new ArrayList<String>(size + dynamicImportPackageStems.length);
				for (int i = 0; i < dynamicImportPackageStems.length; i++) {
					stems.add(dynamicImportPackageStems[i]);
				}
			}

			List<String> names;
			if (dynamicImportPackages == null) {
				names = new ArrayList<String>(size);
			} else {
				names = new ArrayList<String>(size + dynamicImportPackages.length);
				for (int i = 0; i < dynamicImportPackages.length; i++) {
					names.add(dynamicImportPackages[i]);
				}
			}

			for (int i = 0; i < size; i++) {
				String name = packages[i];
				if (isDynamicallyImported(name))
					continue;
				if (name.equals("*")) { /* shortcut *///$NON-NLS-1$
					dynamicAllPackages = true;
					return;
				}

				if (name.endsWith(".*")) //$NON-NLS-1$
					stems.add(name.substring(0, name.length() - 1));
				else
					names.add(name);
			}

			size = stems.size();
			if (size > 0)
				dynamicImportPackageStems = stems.toArray(new String[size]);

			size = names.size();
			if (size > 0)
				dynamicImportPackages = names.toArray(new String[size]);
		}
	}

	/**
	 * Adds a list of DynamicImport-Package manifest elements to the dynamic
	 * import tables of this BundleLoader.  Duplicate packages are checked and
	 * not added again.
	 * @param packages the DynamicImport-Package elements to add.
	 */
	public final void addDynamicImportPackage(ManifestElement[] packages) {
		if (packages == null)
			return;
		List<String> dynamicImports = new ArrayList<String>(packages.length);
		StringBuilder importSpec = new StringBuilder();
		for (ManifestElement dynamicImportElement : packages) {
			String[] names = dynamicImportElement.getValueComponents();
			for (String name : names)
				dynamicImports.add(name);
			if (importSpec.length() > 0) {
				importSpec.append(',');
			}
			importSpec.append(dynamicImportElement.toString());
		}

		if (dynamicImports.size() > 0) {
			addDynamicImportPackage(dynamicImports.toArray(new String[dynamicImports.size()]));

			Map<String, String> dynamicImportMap = new HashMap<String, String>();
			dynamicImportMap.put(Constants.DYNAMICIMPORT_PACKAGE, importSpec.toString());

			try {
				ModuleRevisionBuilder builder = OSGiManifestBuilderFactory.createBuilder(dynamicImportMap);
				wiring.addDynamicImports(builder);
			} catch (BundleException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

		}
	}

	/*
	 * Finds a packagesource that is either imported or required from another bundle.
	 * This will not include an local package source
	 */
	private PackageSource findSource(String pkgName) {
		if (pkgName == null)
			return null;
		PackageSource result = findImportedSource(pkgName, null);
		if (result != null)
			return result;
		// Note that dynamic imports are not checked to avoid aggressive wiring (bug 105779)  
		return findRequiredSource(pkgName, null);
	}

	private PackageSource findImportedSource(String pkgName, Collection<BundleLoader> visited) {
		KeyedHashSet imports = getImportedSources(visited);
		if (imports == null)
			return null;
		synchronized (imports) {
			return (PackageSource) imports.getByKey(pkgName);
		}
	}

	public KeyedHashSet getImportedSources(Collection<BundleLoader> visited) {
		synchronized (importedSources) {
			if (importsInitialized) {
				return importedSources;
			}
			List<ModuleWire> importWires = wiring.getRequiredModuleWires(PackageNamespace.PACKAGE_NAMESPACE);
			for (ModuleWire importWire : importWires) {
				PackageSource source = createExportPackageSource(importWire, visited);
				if (source != null) {
					importedSources.add(source);
				}
			}
			importsInitialized = true;
			return importedSources;
		}
	}

	private PackageSource findDynamicSource(String pkgName) {
		if (!isExportedPackage(pkgName) && isDynamicallyImported(pkgName)) {
			ModuleRevision revision = wiring.getRevision();
			try {
				ModuleWire dynamicWire = revision.getRevisions().getModule().getContainer().resolveDynamic(pkgName, revision);
				if (dynamicWire != null) {
					PackageSource source = createExportPackageSource(dynamicWire, null);
					synchronized (importedSources) {
						importedSources.add(source);
					}
					return source;
				}
			} catch (ResolutionException e) {
				// do nothing;
			}
		}
		return null;
	}

	private PackageSource findRequiredSource(String pkgName, Collection<BundleLoader> visited) {
		synchronized (requiredSources) {
			PackageSource result = (PackageSource) requiredSources.getByKey(pkgName);
			if (result != null)
				return result.isNullSource() ? null : result;
		}
		if (visited == null)
			visited = new ArrayList<BundleLoader>();
		if (!visited.contains(this))
			visited.add(this); // always add ourselves so we do not recurse back to ourselves
		List<PackageSource> result = new ArrayList<PackageSource>(3);
		List<ModuleWire> requireBundles = wiring.getRequiredModuleWires(BundleNamespace.BUNDLE_NAMESPACE);
		for (ModuleWire bundleWire : requireBundles) {
			((BundleLoader) bundleWire.getProviderWiring().getModuleLoader()).addExportedProvidersFor(pkgName, result, visited);
		}
		// found some so cache the result for next time and return
		PackageSource source;
		if (result.size() == 0) {
			// did not find it in our required bundles lets record the failure
			// so we do not have to do the search again for this package.
			source = NullPackageSource.getNullPackageSource(pkgName);
		} else if (result.size() == 1) {
			// if there is just one source, remember just the single source 
			source = result.get(0);
		} else {
			// if there was more than one source, build a multisource and cache that.
			PackageSource[] srcs = result.toArray(new PackageSource[result.size()]);
			source = createMultiSource(pkgName, srcs);
		}
		synchronized (requiredSources) {
			requiredSources.add(source);
		}
		return source.isNullSource() ? null : source;
	}

	/*
	 * Gets the package source for the pkgName.  This will include the local package source
	 * if the bundle exports the package.  This is used to compare the PackageSource of a 
	 * package from two different bundles.
	 */
	public final PackageSource getPackageSource(String pkgName) {
		PackageSource result = findSource(pkgName);
		if (!isExportedPackage(pkgName))
			return result;
		// if the package is exported then we need to get the local source
		PackageSource localSource = exportSources.getPackageSource(pkgName);
		if (result == null)
			return localSource;
		if (localSource == null)
			return result;
		return createMultiSource(pkgName, new PackageSource[] {result, localSource});
	}

	static final class ClassContext extends SecurityManager {
		// need to make this method public
		public Class<?>[] getClassContext() {
			return super.getClassContext();
		}
	}
}