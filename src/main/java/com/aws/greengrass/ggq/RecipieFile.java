/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.aws.greengrass.ggq;

import static com.aws.greengrass.ggq.TemplateCommand.*;
import com.vdurmont.semver4j.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

class RecipieFile {
    private String body;
    String name;
    Semver version;
    String description;
    String publisher;
    String group;
    String hashbang;
    String filename;
    boolean isRecipe;
    RecipieFile(String fn, String b, boolean is) {
        isRecipe = is;
        filename = fn;
        setBody(b);
    }
    public final void setBody(String b) {
        if(b==null) return;
        String fnName = TemplateCommand.chopExtension(filename);
        Matcher versionStart = Pattern.compile("-[0-9]").matcher(fnName);
        String p1;
        String p2;
        if (versionStart.find()) {
            p1 = fnName.substring(0, versionStart.start());
            p2 = fnName.substring(versionStart.start() + 1);
        } else {
            p1 = fnName;
            p2 = "0.0.0";
        }
        body = b;
        name = getPart("name", p1);
        version = new Semver(cleanVersion(getPart("version", p2)),
                Semver.SemverType.NPM);
        description = getPart("description", null);
        publisher = getPart("publisher", null);
        group = getPart("Group", null);
        {
            Matcher m = Pattern.
                    compile("component[ -_]?config(uration)?: *([-a-z0-9_-]+) *[=:] *([^\n]+)",
                            Pattern.CASE_INSENSITIVE)
                    .matcher(body);
            while (m.find())
                configuration.put(m.group(2), m.group(3));
        }
        Matcher hashbangp = Pattern.compile("#! *(.*)").matcher(body);
        hashbang = hashbangp.lookingAt() ? hashbangp.group(1) : null;
    }
    final Map<String, String> configuration = new LinkedHashMap<>();
    final Map<String, String> dependencies = new LinkedHashMap<>();
    public static String cleanVersion(String version) {
        if (isEmpty(version))
            version = "0.0.0";
        if (version.endsWith("-SNAPSHOT"))
            version = version.substring(0, version.length() - 9);
        return version;
    }
    private String getPart(String part, String dflt) {
        Matcher m = Pattern.
                compile("component[ -_]?" + part + ": *([^;\n\"]+)", Pattern.CASE_INSENSITIVE)
                .matcher(body);
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
            Path fn = dir.resolve(name + '-' + version + ".yaml");
            try (final Writer out = Files.
                    newBufferedWriter(fn, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                out.write(body);
            }
        }
    }
    public void injectArtifactRefs(String artifactURL) throws IOException {
        if (isRecipe)
            body = body.replaceAll("(?i)artifacts: *inject",
                    artifactURL == null ? "# no artifacts"
                            : "artifacts: [{ unarchive: ZIP, uri: '"
                            + artifactURL + "' }]");
    }
    public void upload(CloudOps cloud) throws IOException {
        if (isRecipe)
            cloud.uploadRecipe(name, version, body);
    }
    @Override public String toString() {
        return "recipie " + name + " - " + version;
    }
}
