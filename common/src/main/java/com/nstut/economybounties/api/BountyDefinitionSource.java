package com.nstut.economybounties.api;

import java.util.Collection;

@FunctionalInterface
public interface BountyDefinitionSource {
    Collection<BountyDefinition> load() throws Exception;
}
