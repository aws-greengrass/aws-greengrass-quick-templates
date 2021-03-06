/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.aws.greengrass.ggq;

import com.aws.greengrass.ggq.CloudOps;
import com.vdurmont.semver4j.*;
import org.junit.jupiter.api.*;
import software.amazon.awssdk.regions.*;
import software.amazon.awssdk.services.greengrassv2.model.*;

public class CloudOpsTest {
    static final boolean runTest = CloudOps.haveAWScreds;
    CloudOps cloud = CloudOps.of(Region.US_WEST_2);
    @Test
    public void T1() {
        if(!runTest) return;
        System.out.println("In CloudOpsTest T1");
        try {
        cloud.uploadRecipe("lua", new Semver("5.3.0"), "---\n"
                + "recipeFormatVersion: 2020-01-25\n"
                + "componentName: lua\n"
                + "componentVersion: 5.3.0\n"
                + "componentDescription: The lua platform\n"
                + "componentPublisher: Amazon\n"
                + "\n"
                + "## A Manifest section with all the usual Platforms\n"
                + "manifests:\n"
                + "  - platform:\n"
                + "      os: windows\n"
                + "    name: \"Windows\"\n"
                + "    selections: [windows]\n"
                + "  - platform:\n"
                + "      os: darwin\n"
                + "    name: \"MacOS\"\n"
                + "    selections: [macos,posix]\n"
                + "# differentiating versions of linux is currently incomplete\n"
                + "  - platform:\n"
                + "      os: all\n"
                + "    name: \"Posix\"\n"
                + "    selections: [posix]\n"
                + "\n"
                + "lifecycle:\n"
                + "  install:\n"
                + "    skipif: onpath lua5.3\n"
                + "    script:\n"
                + "        posix:\n"
                + "            script: |\n"
                + "                if which apt-get;\n"
                + "                    then apt-get install -y lua5.3 \n"
                + "                elif which yum;\n"
                + "                    then yum install epel-release && yum install lua\n"
                + "                else echo No support for this flavor of linux; exit 1;\n"
                + "                fi\n"
                + "            requiresPrivilege: true\n"
                + "        macos: brew install lua\n"
                + "        windows: head explodes!");
        } catch(Throwable t) {
            t.printStackTrace(System.out);
        }
        cloud.components().forEach(c -> {
            System.out.println(c.componentName() + " " + c.arn());
            ComponentLatestVersion lv = c.latestVersion();
            System.out.println("\t"+lv
            +"\n\t"+lv.componentVersion()
            +"\n\t"+lv.description()
            +"\n\t"+lv.arn());
            c.sdkFields().forEach(
                    f -> System.out.println(
                            "  " + f.unmarshallLocationName()
                            + " = " + f));
//            SdkField lv = c.
        });
    }
    @Test
    public void T2() {
        if(!runTest) return;
        System.out.println("In CloudOpsTest T2");
        cloud.coreDevices().forEach(c -> {
            System.out.println(c.coreDeviceThingName() + " " + c.
                    statusAsString());
            c.sdkFields().forEach(
                    f -> System.out.println(
                            "  " + f.unmarshallLocationName()
                            + " = " + f));
        });
    }
}
