/*
 * Copyright (c) 2024-2025 Stefan Toengi
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package de.schnippsche.solarreader.test;

import de.schnippsche.solarreader.backend.connection.usb.UsbConnection;
import java.io.IOException;
import java.net.ConnectException;
import org.tinylog.Logger;

public class DalyBmsUsbConnection implements UsbConnection {

  private String result = null;
  private boolean open = false;
  private boolean markError = false;

  public void setError(boolean markError) {
    this.markError = markError;
  }

  @Override
  public void connect() throws ConnectException {
    Logger.debug("openPort");
    if (open) {
      throw new ConnectException("Port already in use");
    }
    open = true;
  }

  @Override
  public int readByte() throws IOException {
    if (result != null && !result.isEmpty() && !markError) {
      String hex = result.substring(0, 2);
      result = result.substring(2);
      return Integer.parseInt(hex, 16) & 0xFF;
    }
    throw new IOException("No more result found");
  }

  @Override
  public int writeBytes(byte[] bytes) throws IOException {

    assert (bytes != null);
    int command = (bytes[2] & 0xFF);

    switch (command) {
      case 0x90:
        result = "a501900802100000763001f4eb";
        break;
      case 0x91:
        result = "a50191080ec8050e9c0901f4c2";
        break;
      case 0x92:
        result = "a50192083d013d019c0901f456";
        break;
      case 0x93:
        result = "a50193080001011000004e20c1";
        break;
      case 0x94:
        result = "0000a50194080e0100000200002073";
        break;
      case 0x95:
        result =
            "a5019508010ec00ec20ec720d7a5019508020ec80ec80ec620e5a5019508030ec20ec20e9c20b0a5019508040ec40ec40ec420dda5019508050ec30ec00ec420d9";
        break;
      case 0x96:
        result = "a5019608013d00000000000082a5019608020000000000000046";
        break;
      case 0x97:
        result = "a5019708000111100000000067";
        break;
      case 0x98:
        result = "a5019808000011110000000068";
        break;
      case 0xd8:
        result = "a501d808000000000000000086";
        break;
      default:
        Logger.error("unknown command: {}", command);
        throw new IOException("unknown command");
    }
    return 0;
  }

  @Override
  public void disconnect() {
    Logger.debug("closePort");
    if (open) {
      open = false;
    } else throw new RuntimeException("closed port without open");
  }
}
