/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.aws.greengrass.ggq;

import java.io.*;

public class CopyThread extends Thread {
    private final BufferedInputStream in;
    private final boolean error;
    private final LineReceiver lr;
    public CopyThread(InputStream inputStream, LineReceiver lineReceiver, boolean b) {
        in = buffered(inputStream);
        lr = lineReceiver;
        error = b;
    }
    private static BufferedInputStream buffered(InputStream in) {
        return in instanceof BufferedInputStream ? (BufferedInputStream) in
                : new BufferedInputStream(in);
    }
    @Override
    public void run() {
        try {
            final StringBuilder sb = new StringBuilder();
            while (true) {
                int c = in.read();
                if (c < 0) return;
                if (c == '\n' || c == '\r') {
                    if (sb.length() > 0) {
                        lr.accept(sb.toString(), error);
                        sb.setLength(0);
                    }
                } else sb.append((char) c);
            }
        } catch (IOException ex) {
        }
    }
}
