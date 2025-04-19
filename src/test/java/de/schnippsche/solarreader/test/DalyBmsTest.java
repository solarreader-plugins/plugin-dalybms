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

import de.schnippsche.solarreader.backend.connection.general.ConnectionFactory;
import de.schnippsche.solarreader.backend.connection.usb.UsbConnection;
import de.schnippsche.solarreader.database.ProviderData;
import de.schnippsche.solarreader.plugins.dalybms.DalyBms;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DalyBmsTest {
  @Test
  void test() throws Exception {
    GeneralTestHelper generalTestHelper = new GeneralTestHelper();
    DalyBmsUsbConnection connection = new DalyBmsUsbConnection();
    ConnectionFactory<UsbConnection> testFactory = knownConfiguration -> connection;
    ProviderData providerData = new ProviderData();
    providerData.setName("DalyBMS Test");
    providerData.setPluginName("DalyBms");
    DalyBms provider = new DalyBms(testFactory);
    providerData.setSetting(provider.getDefaultProviderSetting());
    provider.setProviderData(providerData);
    generalTestHelper.testProviderInterface(provider);
    Map<String, Object> variables = providerData.getResultVariables();
    assert new BigDecimal(1).equals(variables.get("count_sensors"));
    assert new BigDecimal(14).equals(variables.get("Zellenanzahl"));
    assert new BigDecimal(16).equals(variables.get("BMS_Zyklen"));
    assert new BigDecimal("3.74").equals(variables.get("Min_Spannung"));
    assert new BigDecimal("3.784").equals(variables.get("Max_Spannung"));
    assert new BigDecimal("25.6").equals(variables.get("Ampere"));
    // Error behavior
    variables.clear();
    connection.setError(true);
    boolean result = provider.doActivityWork(variables);
    assert (!result);
  }
}
