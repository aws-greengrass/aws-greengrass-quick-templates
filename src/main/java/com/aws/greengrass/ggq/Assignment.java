/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.aws.greengrass.ggq;

public class Assignment {
    final String component;
    final String key;
    final String value;
    boolean inEnv;
    public Assignment(String stmt) {
        int eq = stmt.indexOf('=');
        if (eq < 0)
            throw new IllegalArgumentException("Expected an = in assignment: " + stmt);
        String lhs = stmt.substring(0, eq).trim();
        String rhs = stmt.substring(eq + 1).trim();
        int colon = lhs.indexOf(':');
        if (colon < 0) {
            switch (lhs) { // a few short synonyms
                case "ll":
                    lhs = "nucleus:logging.level";
                    rhs = rhs.toUpperCase();
                    if (rhs.startsWith("D")) rhs = "DEBUG";
                    else if (rhs.startsWith("I")) rhs = "INFO";
                    else if (rhs.startsWith("W")) rhs = "WARN";
                    else if (rhs.startsWith("E")) rhs = "ERROR";
                    break;
                case "fmt":
                    lhs = "nucleus:logging.format";
                    rhs = rhs.toLowerCase().startsWith("j") ? "JSON" : "TEXT";
                    break;
                case "od": lhs = "nucleus:logging.outputDirectory";
                    break;
                case "jvm": lhs = "nucleus:jvmOptions";
                    break;
                // default: colon = -1; // just happens
            }
            colon = lhs.indexOf(':');
        }
        if (colon > 0) {
            String cmp = lhs.substring(0, colon);
            switch (cmp) {
                case "nucleus": cmp = "aws.greengrass.Nucleus";
                    break;
                case "cli": cmp = "aws.greengrass.Cli";
                    break;
                case "console":
                    cmp = "aws.greengrass.LocalDebugConsole";
                    break;
            }
            component = cmp;
        } else component = null;
        String k = lhs.substring(colon + 1);
        if (inEnv = k.endsWith("-env"))
            k = k.substring(0, k.length() - 4);
        key = k;
        value = rhs;
    }
}
