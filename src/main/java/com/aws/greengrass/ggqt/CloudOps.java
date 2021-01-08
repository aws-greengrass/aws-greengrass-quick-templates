/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.aws.greengrass.ggqt;

import com.vdurmont.semver4j.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.prefs.*;
import java.util.regex.*;
import org.jetbrains.annotations.*;
import software.amazon.awssdk.core.*;
import software.amazon.awssdk.regions.*;
import software.amazon.awssdk.services.greengrassv2.*;
import software.amazon.awssdk.services.greengrassv2.model.*;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;

public class CloudOps {
    String bucket;
    public static CloudOps of(@NotNull Region r) {
        return regions.computeIfAbsent(r, R -> new CloudOps(GreengrassV2Client.
                builder()
                .region(R)
                .build()));
    }
    public static CloudOps of(@NotNull String r) {
        return of(Region.of(r));
    }
    public static CloudOps dflt() {
        if (dfltCloud == null)
            dfltCloud = new CloudOps(GreengrassV2Client.create());
        return dfltCloud;
    }
    private CloudOps(GreengrassV2Client c) {
        requireCredentials();
        client = c;
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
    private Map<String, Component> components = null;
    public synchronized Map<String, Component> getComponents() {
        Map<String, Component> c = components;
        if (c == null) {
            c = components = new HashMap<>();
            components().forEach(nc -> components.put(nc.componentName(), nc));
        }
        return c;
    }
    public Component getComponent(String name) {
        return getComponents().get(name);
    }
    private S3Client s3client;
    public S3Client s3() {
        S3Client s = s3client;
        if (s == null)
            s3client = s = S3Client.create();
        return s;
    }
    public String getBucket() {
        String b = bucket;
        if (b == null) {
            b = prefs.get("bucket", null);
            if (b == null)
                b = "gg2-recipes-" + generateRandomString(10);
            setBucket(b);
        }
        return b;
    }
    private static final Preferences prefs = Preferences.
            userNodeForPackage(CloudOps.class);
    public void setBucket(String b) {
        if (b != null && !b.equals(bucket)) {
            prefs.put("bucket", b);
            try {
                s3().createBucket(bld -> bld.bucket(b));
            } catch (BucketAlreadyOwnedByYouException ioe) {
                // if it's already there, I don't care.
            }
        }
        bucket = b;
    }
    public void putObject(String tag, Path path) {
        s3().putObject(b -> b.bucket(getBucket())
                .contentType("application/zip")
                .key(tag), path);
        System.out.println("Wrote artifacts to s3://" + getBucket()
                        + '/' + tag);
    }
    static final char[] rsChars = "abcdefghijklmnopqrstuvwxyx0123456789".
            toCharArray();
    static final SecureRandom random = new SecureRandom();
    public static String generateRandomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        while (--length >= 0)
            sb.append(rsChars[random.nextInt(rsChars.length)]);
        return sb.toString();
    }
    static final Pattern versionRep =
            Pattern.compile("componentversion: *[0-9][0-9.]*",
                    Pattern.CASE_INSENSITIVE);
    public void uploadRecipe(@NotNull String name, @NotNull Semver version, @NotNull String recipe) {
        Component c = getComponent(name);
        if (c != null) {
            Semver v = new Semver(c.latestVersion().componentVersion());
            if (version.isLowerThanOrEqualTo(v)) {
                version = v.nextPatch();
                System.out.println("Bump version to " + version);
            }
        }
        recipe = versionRep.matcher(recipe)
                .replaceFirst("componentVersion: " + version);
        try {
            client.createComponentVersion(CreateComponentVersionRequest
                    .builder()
                    .recipeSource(RecipeSource.builder()
                            .inlineRecipe(SdkBytes.fromString(recipe, Charset.
                                    forName("UTF-8")))
                            .build()
                    )
                    .build()
            );
        } catch (Throwable t) {
            System.out.println(t);
        }
    }
    public static final boolean haveAWScreds =
            System.getenv("AWS_ACCESS_KEY_ID") != null
            || new File(System.getProperty("user.home", "/tmp") + "/.aws")
                    .isDirectory();
    public static void requireCredentials() {
        if (!haveAWScreds) {
            System.err.println("You need to configure your AWS credentials to "
                    + "upload to the cloud.\n"
                    + "See https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-files.html");
            System.exit(1);
        }
    }
}
