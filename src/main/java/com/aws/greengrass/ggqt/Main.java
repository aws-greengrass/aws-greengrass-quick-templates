/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.aws.greengrass.ggqt;

import java.awt.*;
import java.net.*;
import java.util.*;

public class Main {
    enum Dest {
        GTD, GROUP, ROOT, REGION, BUCKET, NONE
    }
    final String[] args;
    boolean verbose = false;
    public static void main(String[] cmd) {
        int ret = new Main(cmd).exec();
        if(ret!=0) showHelp();
        System.exit(ret&0xFF);
    }
    public Main(String[] args) {
        this.args = args;
    }
    int exec() {
        Dest dest = Dest.NONE;
        ArrayList<String> files = new ArrayList<>();
        TemplateCommand tc = new TemplateCommand();
        for (String s : args)
            switch (dest) {
                case GTD:
                    tc.generatedTemplateDirectory = s;
                    dest = Dest.NONE;
                    break;
                case GROUP:
                    tc.group = s;
                    dest = Dest.NONE;
                    break;
                case ROOT:
                    tc.rootPath = s;
                    dest = Dest.NONE;
                    break;
                case REGION:
                    dest = Dest.NONE;
                    tc.cloud = CloudOps.of(s);
                    break;
                case BUCKET:
                    dest = Dest.NONE;
                    tc.bucket = s;
                    break;
                default:
                    switch (s) {
                        case "-b":
                        case "--bucket":
                            dest = Dest.BUCKET;
                            break;
                        case "-r":
                        case "--ggcRootPath":
                            dest = Dest.ROOT;
                            break;
                        case "-gtd":
                            dest = Dest.GTD;
                            break;
                        case "-g":
                        case "--group":
                            dest = Dest.GROUP;
                            break;
                        case "-dr":
                        case "--dryrun":
                            tc.dryrun = true;
                            break;
                        case "--to":
                        case "-to":
                            dest = Dest.REGION;
                            tc.dryrun = true;
                            break;
                        case "--upload":
                        case "-u":
                            tc.cloud = CloudOps.dflt();
                            tc.dryrun = true;
                            break;
                        case "--verbose":
                        case "-v":
                            verbose = true;
                            break;
                        case "--help":
                        case "-h":
                            return 256; // sic
                        default:
                            if(s.startsWith("-")) {
                                System.out.println("Illegal argument: "+s);
                                return 1;
                            }
                            files.add(s);
                            break;
                    }
                    break;
            }
        tc.files = files.toArray(new String[files.size()]);
        try {
            tc.run();
            return 0;
        } catch (Throwable t) {
            Throwable c = t;
            while (c.getCause() != null)
                c = c.getCause();
            String m = c.getLocalizedMessage();
            if (m == null || m.length() == 0) m = c.toString();
            System.out.println(m);
            if(verbose) t.printStackTrace(System.out);
            return 1;
        }
    }
    private static final String helpUrl = "https://github.com/aws-greengrass/"
            + "aws-greengrass-quick-templates/blob/main/README.md";
    @SuppressWarnings("TooBroadCatch")
    public static void showHelp() {
        System.out.println("Help is available at "+helpUrl);
        try {
            Desktop.getDesktop().browse(new URI(helpUrl));
        } catch(Throwable t) {}
    }
}
