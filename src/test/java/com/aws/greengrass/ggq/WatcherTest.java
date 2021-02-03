/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.aws.greengrass.ggq;

import com.aws.greengrass.ggq.Main;
import org.junit.jupiter.api.*;

/**
 *
 * @author jag
 */
public class WatcherTest {
    
    
    public void test_single_file(String name) {
        try {
            System.out.println("Testing " + name);
            Assertions.assertTrue(run("--ggcRootPath",
                    "/opt/GGv2", "--watch", "-v") == 0);
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            Assertions.fail(t.toString());
        }
    }
    public int run(String... args) {
        return new Main(args).exec();
    }


    /**
     * Test of watch method, of class Watcher.
     */
    @Test
    public void testWatch() {
        Thread t = new Thread() {
            { setDaemon(true); }
            @Override public void run() {
                test_single_file("foo");
                Assertions.fail();
            }
        };
        t.start();
        try {
            t.join(4000);
        } catch (InterruptedException ex) { Assertions.fail(); }
    }
}
