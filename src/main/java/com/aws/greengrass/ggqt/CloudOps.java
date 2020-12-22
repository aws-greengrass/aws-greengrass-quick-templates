/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.aws.greengrass.ggqt;

import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import org.jetbrains.annotations.*;
import software.amazon.awssdk.core.*;
import software.amazon.awssdk.regions.*;
import software.amazon.awssdk.services.greengrassv2.*;
import software.amazon.awssdk.services.greengrassv2.model.*;

public class CloudOps {
    public static CloudOps of(@NotNull Region r) {
        return regions.computeIfAbsent(r, R -> new CloudOps(R));
    }
    public static CloudOps of(@NotNull String r) {
        return of(Region.of(r));
    }
    public static CloudOps dflt() {
        if (dfltCloud == null)
            dfltCloud = new CloudOps();
        return dfltCloud;
    }
    private CloudOps(@NotNull Region r) {
        client = GreengrassV2Client.builder()
                .region(r)
                .build();
    }
    private CloudOps() {
        client = GreengrassV2Client.builder()
                .build();
    }
    static final ConcurrentHashMap<Region, CloudOps> regions = new ConcurrentHashMap<>();
    static CloudOps dfltCloud = null;
    private final GreengrassV2Client client;
    public List<Component> components() {
        return client.listComponents(b -> b.build()).components();
    }
    public List<CoreDevice> coreDevices() {
        return client.listCoreDevices(b -> b.build()).coreDevices();
    }
    public void uploadRecipe(@NotNull String recipe) {
        client.createComponentVersion(CreateComponentVersionRequest.builder()
                .recipeSource(RecipeSource.builder()
                        .inlineRecipe(SdkBytes.fromString(recipe, Charset.
                                forName("UTF-8")))
                        .build()
                )
                .build()
        );
    }
}
