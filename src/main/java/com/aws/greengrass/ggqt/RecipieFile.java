/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.aws.greengrass.ggqt;

import static com.aws.greengrass.ggqt.TemplateCommand.*;
import com.vdurmont.semver4j.*;
import java.io.*;
import java.nio.file.*;
import java.util.regex.*;

class RecipieFile {
    private final String body;
    final String name;
    final Semver version;
    final String description;
    final String publisher;
    final String group;
    final String hashbang;
    final String filename;
    final boolean isRecipe;
    RecipieFile(String fn, String b, boolean is) {
        isRecipe = is;
        filename = fn;
        String fnName = TemplateCommand.chopExtension(fn);
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
        Matcher m = Pattern.compile("#! *(.*)").matcher(body);
        hashbang = m.lookingAt() ? m.group(1) : null;
    }
    public static String cleanVersion(String version) {
        if (isEmpty(version))
            version = "0.0.0";
        if (version.endsWith("-SNAPSHOT"))
            version = version.substring(0, version.length() - 9);
        return version;
    }
    private String getPart(String part, String dflt) {
        Matcher m = Pattern.
                compile("component[ -_]?" + part + ": *([^;,\n\"]+)", Pattern.CASE_INSENSITIVE)
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
    public void upload(CloudOps cloud, String artifactURL) throws IOException {
        if (isRecipe) {
            /* Most of the work here is updating the artifacts: clauses */
            StringBuilder sb = new StringBuilder();
            int pst = 0;
            Matcher m = Pattern
                    .compile("^([ \t]*)artifacts:.*\n",
                            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE)
                    .matcher(body);
            while (m.find()) {
                sb.append(body, pst, m.start());
                pst = m.end();
                if (artifactURL != null) {
                    String prefix = m.group(1);
                    sb.append(prefix)
                            .append("artifacts:\n")
                            .append(prefix)
                            .append("  - uri: ")
                            .append(artifactURL)
                            .append('\n')
                            .append(prefix)
                            .append("    unarchive: ZIP\n");
                }
            }
            sb.append(body, pst, body.length());
            cloud.uploadRecipe(name, version, sb.toString());
        }
    }
}
