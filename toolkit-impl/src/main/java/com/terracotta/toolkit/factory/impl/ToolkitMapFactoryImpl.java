/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.terracotta.toolkit.factory.impl;

import org.terracotta.toolkit.ToolkitObjectType;
import org.terracotta.toolkit.config.Configuration;
import org.terracotta.toolkit.internal.ToolkitInternal;

import com.tc.platform.PlatformService;
import com.terracotta.toolkit.collections.DestroyableToolkitMap;
import com.terracotta.toolkit.collections.map.ToolkitMapImpl;
import com.terracotta.toolkit.factory.ToolkitFactoryInitializationContext;
import com.terracotta.toolkit.factory.ToolkitObjectFactory;
import com.terracotta.toolkit.roots.impl.ToolkitTypeConstants;
import com.terracotta.toolkit.type.IsolatedClusteredObjectLookup;
import com.terracotta.toolkit.type.IsolatedToolkitTypeFactory;

/**
 * An implementation of {@link ToolkitMapFactory}
 */
public class ToolkitMapFactoryImpl extends AbstractPrimaryToolkitObjectFactory<DestroyableToolkitMap, ToolkitMapImpl> {

  public ToolkitMapFactoryImpl(ToolkitInternal toolkit, ToolkitFactoryInitializationContext context) {
    super(toolkit, context.getToolkitTypeRootsFactory()
        .createAggregateIsolatedTypeRoot(ToolkitTypeConstants.TOOLKIT_MAP_ROOT_NAME,
                                         new MapIsolatedTypeFactory(context.getPlatformService()),
                                         context.getPlatformService()));
  }

  @Override
  public ToolkitObjectType getManufacturedToolkitObjectType() {
    return ToolkitObjectType.MAP;
  }

  private static class MapIsolatedTypeFactory implements
      IsolatedToolkitTypeFactory<DestroyableToolkitMap, ToolkitMapImpl> {

    private final PlatformService platformService;

    MapIsolatedTypeFactory(PlatformService platformService) {
      this.platformService = platformService;
    }

    @Override
    public DestroyableToolkitMap createIsolatedToolkitType(ToolkitObjectFactory<DestroyableToolkitMap> factory,
                                                           IsolatedClusteredObjectLookup<ToolkitMapImpl> lookup,
                                                           String name, Configuration config,
                                                           ToolkitMapImpl tcClusteredObject) {
      return new DestroyableToolkitMap(factory, lookup, tcClusteredObject, name, platformService);
    }

    @Override
    public ToolkitMapImpl createTCClusteredObject(Configuration config) {
      return new ToolkitMapImpl(platformService);
    }

  }

}
