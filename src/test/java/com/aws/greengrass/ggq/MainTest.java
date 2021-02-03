/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.aws.greengrass.ggq;

import com.aws.greengrass.ggq.Main;
import org.junit.jupiter.api.*;

public class MainTest {
    @Test void t1() {
        Assertions.assertEquals(0, run("--dryrun", "hello:msg=23", "fmt=json", "jvm=-Xmx32m"));
    }
    public int run(String... args) {
        return new Main(args).exec();
    }

    
}
