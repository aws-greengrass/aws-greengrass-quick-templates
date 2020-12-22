/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.aws.greengrass.ggqt;

import java.io.*;
import java.nio.file.*;
import java.util.regex.*;

class RecipieFile {
    private final String body;
    final String componentName;
    final String componentVersion;
    final String componentDescription;
    final String componentPublisher;
    final String group;
    final String hashbang;
    final String filename;
    final boolean isRecipe;
    RecipieFile(String fn, String b, boolean is) {
        isRecipe = is;
        filename = fn;
        String name = TemplateCommand.chopExtension(fn);
        Matcher version = Pattern.compile("-[0-9]").matcher(name);
        String p1;
        String p2;
        if (version.find()) {
            p1 = name.substring(0, version.start());
            p2 = name.substring(version.start() + 1);
        } else {
            p1 = name;
            p2 = "0.0.0";
        }
        body = b;
        componentName = getPart("name", p1);
        componentVersion = TemplateCommand.cleanVersion(getPart("version", p2));
        componentDescription = getPart("description", null);
        componentPublisher = getPart("publisher", null);
        group = getPart("Group", null);
        Matcher m = Pattern.compile("#! *(.*)").matcher(body);
        hashbang = m.lookingAt() ? m.group(1) : null;
    }
    private String getPart(String part, String dflt) {
        Matcher m = Pattern.
                compile("component[ -_]?" + part + ": *([^;,\n\"]+)", Pattern.CASE_INSENSITIVE).
                matcher(body);
        return clean(m.find() ? m.group(1) : dflt, dflt);
    }
    private String clean(String s, String dflt) {
        if (s == null) return dflt;
        Matcher m = Pattern.compile(" *([^ #]+)").matcher(s);
        if (m.lookingAt()) s = m.group(1);
        s = s.trim();
        return TemplateCommand.isEmpty(s) ? dflt : s;
    }
    public void write(Path dir) throws IOException {
        if (isRecipe) {
            Path fn = dir.
                    resolve(componentName + '-' + componentVersion + ".yaml");
            System.out.println("Writing " + fn);
            try (final Writer out = Files.
                    newBufferedWriter(fn, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                out.write(body);
            }
        }
    }
    public void upload(CloudOps cloud) throws IOException {
        if (isRecipe) {
            System.out.println("Uploading "+componentName+'-'+componentVersion);
            cloud.uploadRecipe(body);
        }
    }
}
