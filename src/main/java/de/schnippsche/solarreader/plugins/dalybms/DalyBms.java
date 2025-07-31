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
package de.schnippsche.solarreader.plugins.dalybms;

import de.schnippsche.solarreader.backend.calculator.ByteArrayCalculator;
import de.schnippsche.solarreader.backend.connection.general.ConnectionFactory;
import de.schnippsche.solarreader.backend.connection.usb.UsbConnection;
import de.schnippsche.solarreader.backend.connection.usb.UsbConnectionFactory;
import de.schnippsche.solarreader.backend.field.FieldType;
import de.schnippsche.solarreader.backend.field.PropertyField;
import de.schnippsche.solarreader.backend.field.PropertyFieldBuilder;
import de.schnippsche.solarreader.backend.frame.DalyFrame;
import de.schnippsche.solarreader.backend.protocol.DalyProtocol;
import de.schnippsche.solarreader.backend.protocol.Protocol;
import de.schnippsche.solarreader.backend.provider.AbstractUsbProvider;
import de.schnippsche.solarreader.backend.provider.CommandProviderProperty;
import de.schnippsche.solarreader.backend.provider.ProviderProperty;
import de.schnippsche.solarreader.backend.table.Table;
import de.schnippsche.solarreader.backend.table.TableCell;
import de.schnippsche.solarreader.backend.table.TableColumn;
import de.schnippsche.solarreader.backend.table.TableColumnType;
import de.schnippsche.solarreader.backend.util.SerialPortConfigurationBuilder;
import de.schnippsche.solarreader.backend.util.Setting;
import de.schnippsche.solarreader.backend.util.TimeEvent;
import de.schnippsche.solarreader.database.Activity;
import de.schnippsche.solarreader.database.DayValue;
import de.schnippsche.solarreader.database.ProviderData;
import de.schnippsche.solarreader.frontend.ui.HtmlInputType;
import de.schnippsche.solarreader.frontend.ui.HtmlWidth;
import de.schnippsche.solarreader.frontend.ui.UIInputElementBuilder;
import de.schnippsche.solarreader.frontend.ui.UIList;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.tinylog.Logger;

/**
 * The {@link DalyBms} class is a provider class for interacting with a Daly Battery Management
 * System (BMS) via USB. It extends {@link AbstractUsbProvider} and handles the communication
 * protocol for interacting with the Daly BMS, including reading sensor and cell data.
 *
 * <p>This class provides the necessary functionality to establish a USB connection to the Daly BMS,
 * process the communication using the {@link DalyProtocol}, and manage the interaction with the
 * BMS. It is primarily used to retrieve battery data such as the number of cells and sensors in the
 * BMS.
 */
public class DalyBms extends AbstractUsbProvider {
  private static final String ENTLADE_WH = "EntladeWh";
  private static final String LADE_WH = "LadeWh";
  private static final String LADELEISTUNG = "Ladeleistung";
  private static final String ENTLADELEISTUNG = "Entladeleistung";
  private static final String COUNT_CELLS = "count_cells";
  private static final String COUNT_SENSORS = "count_sensors";
  private static final int MAX_RETRIES = 2;
  private static final int RETRY_DELAY_MILLIS = 100;
  private final Protocol<DalyFrame> protocol;
  private final ByteArrayCalculator byteArrayCalculator;
  private DayValue entladeWh;
  private DayValue ladeWh;
  private Integer countSensors;
  private Integer countCells;

  /**
   * Constructs a new instance of the {@link DalyBms} class using the default USB connection
   * factory. This constructor initializes the connection to the Daly BMS using the default USB
   * connection configuration. It also sets the default number of cells (48) and sensors (16) in the
   * BMS.
   */
  public DalyBms() {
    this(new UsbConnectionFactory());
  }

  /**
   * Constructs a new instance of the {@link DalyBms} class with a custom {@link ConnectionFactory}
   * for managing USB connections. This constructor provides flexibility by allowing a custom USB
   * connection factory, useful for scenarios that require specific configurations for USB
   * communication.
   *
   * @param connectionFactory the {@link ConnectionFactory} to use for creating USB connections
   */
  public DalyBms(ConnectionFactory<UsbConnection> connectionFactory) {
    super(connectionFactory);
    this.protocol = new DalyProtocol();
    this.countCells = 16;
    this.countSensors = 8;
    this.byteArrayCalculator = new ByteArrayCalculator();
    Logger.debug("instantiate {}", this.getClass().getName());
  }

  /**
   * Returns the {@link ResourceBundle} for the plugin based on the specified {@link Locale}.
   *
   * <p>This implementation retrieves the resource bundle associated with the plugin, which provides
   * localized content for the given {@link Locale}. The returned {@link ResourceBundle} is
   * predefined and does not change based on the locale parameter in this implementation.
   *
   * @return the {@link ResourceBundle} associated with the plugin.
   */
  @Override
  public ResourceBundle getPluginResourceBundle() {
    return ResourceBundle.getBundle("dalybms", locale);
  }

  @Override
  public void setProviderData(ProviderData providerData) {
    this.providerData = providerData;
    countCells = providerData.getSetting().getConfigurationValueAsInteger(COUNT_CELLS, 16);
    countSensors = providerData.getSetting().getConfigurationValueAsInteger(COUNT_SENSORS, 8);
    configurationHasChanged();
  }

  @Override
  public Activity getDefaultActivity() {
    return new Activity(TimeEvent.SUNRISE, -60, TimeEvent.SUNSET, 3600, 60, TimeUnit.SECONDS);
  }

  @Override
  public Optional<UIList> getProviderDialog() {
    UIList uiList = new UIList();
    uiList.addElement(
        new UIInputElementBuilder()
            .withId("id-address")
            .withRequired(true)
            .withType(HtmlInputType.TEXT)
            .withColumnWidth(HtmlWidth.HALF)
            .withLabel(resourceBundle.getString("dalybms.address.text"))
            .withName(Setting.PROVIDER_ADDRESS)
            .withPlaceholder(resourceBundle.getString("dalybms.address.text"))
            .withTooltip(resourceBundle.getString("dalybms.address.tooltip"))
            .withInvalidFeedback(resourceBundle.getString("dalybms.address.error"))
            .build());
    return Optional.of(uiList);
  }

  @Override
  public Optional<List<ProviderProperty>> getSupportedProperties() {
    List<ProviderProperty> allProperties = new ArrayList<>();
    Optional<List<ProviderProperty>> defaultProperties =
        getSupportedPropertiesFromFile("dalybms_fields.json");
    defaultProperties.ifPresent(allProperties::addAll);
    allProperties.add(getCellProperties());
    allProperties.add(getSensorProperties());
    allProperties.add(getStateProperty());
    allProperties.add(getErrorProperties());
    return Optional.of(allProperties);
  }

  @Override
  public Optional<List<Table>> getDefaultTables() {
    List<Table> tables = new ArrayList<>();
    Table table = new Table("Info");
    table.addColumnAndCellAtFirstRow(
        new TableColumn("Produkt", TableColumnType.STRING), new TableCell("\"Daly BMS\""));
    table.addColumnAndCellAtFirstRow(
        new TableColumn("Objekt", TableColumnType.STRING), new TableCell("PROVIDER_NAME"));
    tables.add(table);
    table = new Table("Batterie");
    table.addColumnAndCellAtFirstRow(
        new TableColumn("Strom", TableColumnType.NUMBER), new TableCell("Ampere"));
    table.addColumnAndCellAtFirstRow(
        new TableColumn("SOC", TableColumnType.NUMBER), new TableCell("SOC"));
    table.addColumnAndCellAtFirstRow(
        new TableColumn(ENTLADE_WH, TableColumnType.NUMBER), new TableCell(ENTLADE_WH));
    table.addColumnAndCellAtFirstRow(
        new TableColumn(ENTLADELEISTUNG, TableColumnType.NUMBER), new TableCell(ENTLADELEISTUNG));

    table.addColumnAndCellAtFirstRow(
        new TableColumn(LADE_WH, TableColumnType.NUMBER), new TableCell(LADE_WH));
    table.addColumnAndCellAtFirstRow(
        new TableColumn(LADELEISTUNG, TableColumnType.NUMBER), new TableCell(LADELEISTUNG));

    table.addColumnAndCellAtFirstRow(
        new TableColumn("Ladestrom", TableColumnType.NUMBER), new TableCell("Ladestrom"));
    table.addColumnAndCellAtFirstRow(
        new TableColumn("Entladestrom", TableColumnType.NUMBER), new TableCell("Entladestrom"));
    table.addColumnAndCellAtFirstRow(
        new TableColumn("Ah_Rest", TableColumnType.NUMBER), new TableCell("Ah_Rest"));
    table.addColumnAndCellAtFirstRow(
        new TableColumn("Ladezyklen", TableColumnType.NUMBER),
        new TableCell("Lade_Entlade_Zyklen"));
    table.addColumnAndCellAtFirstRow(
        new TableColumn("Spannung", TableColumnType.NUMBER), new TableCell("Batteriespannung"));
    for (int cell = 1; cell <= countCells; cell++) {
      table.addColumnAndCellAtFirstRow(
          new TableColumn("Spannung_Zelle" + cell, TableColumnType.NUMBER),
          new TableCell("Spannung_Zelle_" + cell));
    }
    for (int cell = 1; cell <= countSensors; cell++) {
      table.addColumnAndCellAtFirstRow(
          new TableColumn("Temperatur_Sensor" + cell, TableColumnType.NUMBER),
          new TableCell("Temperatur_" + cell));
    }
    tables.add(table);
    table = new Table("Service");
    table.addColumnAndCellAtFirstRow(
        new TableColumn("Anz_TempSensoren", TableColumnType.NUMBER),
        new TableCell("Anz_TempSensoren"));
    table.addColumnAndCellAtFirstRow(
        new TableColumn("Zellenanzahl", TableColumnType.NUMBER), new TableCell("Zellenanzahl"));
    table.addColumnAndCellAtFirstRow(
        new TableColumn("Status", TableColumnType.NUMBER), new TableCell("Ladung_Entladung"));
    table.addColumnAndCellAtFirstRow(
        new TableColumn("BMS_Zyklen", TableColumnType.NUMBER), new TableCell("BMS_Zyklen"));
    table.addColumnAndCellAtFirstRow(
        new TableColumn("Ladung_MOS_Status", TableColumnType.NUMBER),
        new TableCell("Ladung_MOS_Status"));
    table.addColumnAndCellAtFirstRow(
        new TableColumn("Entladung_MOS_Status", TableColumnType.NUMBER),
        new TableCell("Entladung_MOS_Status"));
    table.addColumnAndCellAtFirstRow(
        new TableColumn("Max_Temp", TableColumnType.NUMBER), new TableCell("Max_Temperatur"));
    table.addColumnAndCellAtFirstRow(
        new TableColumn("MaxTemp_Zelle", TableColumnType.NUMBER),
        new TableCell("MaxTemp_ZellenNr"));
    table.addColumnAndCellAtFirstRow(
        new TableColumn("Min_Temp", TableColumnType.NUMBER), new TableCell("Min_Temperatur"));
    table.addColumnAndCellAtFirstRow(
        new TableColumn("MinTemp_Zelle", TableColumnType.NUMBER),
        new TableCell("MinTemp_ZellenNr"));
    table.addColumnAndCellAtFirstRow(
        new TableColumn("Max_Spannung", TableColumnType.NUMBER), new TableCell("Max_Spannung"));
    table.addColumnAndCellAtFirstRow(
        new TableColumn("Max_Spannung_Zelle", TableColumnType.NUMBER),
        new TableCell("Max_Spannung_ZellenNr"));
    table.addColumnAndCellAtFirstRow(
        new TableColumn("Min_Spannung", TableColumnType.NUMBER), new TableCell("Min_Spannung"));
    table.addColumnAndCellAtFirstRow(
        new TableColumn("Min_Spannung_Zelle", TableColumnType.NUMBER),
        new TableCell("Min_Spannung_ZellenNr"));
    for (int i = 0; i <= 7; i++) {
      table.addColumnAndCellAtFirstRow(
          new TableColumn("FehlerCode_" + i, TableColumnType.NUMBER),
          new TableCell("Fehlercode_" + i));
    }
    tables.add(table);
    // Balance
    table = new Table("Balance");
    for (int cell = 1; cell <= countCells; cell++) {
      table.addColumnAndCellAtFirstRow(
          new TableColumn("Zelle_" + cell + "_Balance", TableColumnType.NUMBER),
          new TableCell("Zelle_" + cell + "_Balance"));
    }
    tables.add(table);
    return Optional.of(tables);
  }

  @Override
  public Setting getDefaultProviderSetting() {
    return new SerialPortConfigurationBuilder()
        .withBaudrate(9600)
        .withSleepMilliseconds(120)
        .withReadTimeoutMilliseconds(5000)
        .withProviderAddress(64)
        .build();
  }

  @Override
  public String testProviderConnection(Setting providerSetting) throws IOException {
    try (UsbConnection testUsbConnection = connectionFactory.createConnection(providerSetting)) {
      testUsbConnection.connect();
      readNumbersOfCellsAndSensors(testUsbConnection, providerSetting);
      int cols = getNumbersOfCells(providerSetting);
      Logger.debug("Number of cells read: " + cols);
      String message = resourceBundle.getString("dalybms.connection.successful");
      return MessageFormat.format(message, cols);
    } catch (Exception e) {
      throw new IOException(resourceBundle.getString("dalybms.connection.error"));
    }
  }

  @Override
  public void doOnFirstRun() throws IOException {
    ladeWh = providerData.getOrCreateDayValue(LADE_WH);
    entladeWh = providerData.getOrCreateDayValue(ENTLADE_WH);
    // read number of sensors
    try (UsbConnection usbConnection = getConnection()) {
      usbConnection.connect();
      Setting setting = providerData.getSetting();
      readNumbersOfCellsAndSensors(usbConnection, setting);
      countCells = getNumbersOfCells(setting);
      countSensors = getNumbersOfSensors(setting);
      Logger.debug("Number of cells read: " + countCells);
      Logger.debug("Number of sensors read: " + countSensors);
      doStandardFirstRun();
    }
  }

  @Override
  public boolean doActivityWork(Map<String, Object> variables) throws InterruptedException {
    try (UsbConnection usbConnection = getConnection()) {
      usbConnection.connect();
      variables.put(COUNT_CELLS, BigDecimal.valueOf(countCells));
      variables.put(COUNT_SENSORS, BigDecimal.valueOf(countSensors));
      workProperties(usbConnection, variables);
      // DayValues
      ladeWh.addValue(variables.get(LADELEISTUNG));
      variables.put(LADE_WH, ladeWh.getTotalValue());
      entladeWh.addValue(variables.get(ENTLADELEISTUNG));
      variables.put(ENTLADE_WH, entladeWh.getTotalValue());
      return true;
    } catch (IOException e) {
      Logger.error(e.getMessage());
      return false;
    }
  }

  private CommandProviderProperty getErrorProperties() {
    CommandProviderProperty errorProperties = new CommandProviderProperty();
    errorProperties.setName("0x98");
    errorProperties.setCommand("98");
    for (int i = 0; i < 8; i++) {
      PropertyField field =
          new PropertyFieldBuilder()
              .withFieldName(String.format("Fehlercode_%d", i))
              .withFieldType(FieldType.U8)
              .withExpression("value")
              .withOffset(i)
              .withLength(1)
              .withNote(String.format("Battery failure status byte %d", i))
              .build();
      errorProperties.getPropertyFieldList().add(field);
    }
    return errorProperties;
  }

  private CommandProviderProperty getStateProperty() {
    CommandProviderProperty stateProperty = new CommandProviderProperty();
    stateProperty.setName("0x97");
    stateProperty.setCommand("97");
    int current = 1;
    for (int pos = 0; pos < 6; pos++) {
      for (int bit = 0; bit < 8; bit++) {
        if (current <= countCells) {
          PropertyField field =
              new PropertyFieldBuilder()
                  .withFieldName(String.format("Zelle_%d_Balance", current))
                  .withFieldType(FieldType.U8)
                  .withExpression(String.format("value %% %d", 1 << bit))
                  .withOffset(pos)
                  .withLength(1)
                  .withNote("cell balance state, 0 = Close, 1 = Open")
                  .build();
          stateProperty.getPropertyFieldList().add(field);
        }
        current++;
      }
    }
    return stateProperty;
  }

  private CommandProviderProperty getSensorProperties() {
    CommandProviderProperty sensorProperties = new CommandProviderProperty();
    sensorProperties.setName("0x96");
    sensorProperties.setCommand("96");
    for (int sensor = 0; sensor < countSensors; sensor++) {
      PropertyField field =
          new PropertyFieldBuilder()
              .withFieldName(String.format("Temperatur_%d", sensor + 1))
              .withFieldType(FieldType.U8)
              .withExpression("value - 40")
              .withOffset(sensor + 1 + (sensor / 7))
              .withLength(1)
              .withNote(String.format("cell %d temperature", sensor + 1))
              .withUnit("Grad Celsius")
              .build();
      sensorProperties.getPropertyFieldList().add(field);
    }
    return sensorProperties;
  }

  private CommandProviderProperty getCellProperties() {
    CommandProviderProperty cellProperties = new CommandProviderProperty();
    cellProperties.setName("0x95");
    cellProperties.setCommand("95");
    for (int cell = 0; cell < countCells; cell++) {
      PropertyField field =
          new PropertyFieldBuilder()
              .withFieldName(String.format("Spannung_Zelle_%d", cell + 1))
              .withFieldType(FieldType.U16_BIG_ENDIAN)
              .withExpression("value / 1000")
              .withOffset(1 + 2 * cell + (cell / 3) * 2)
              .withLength(2)
              .withNote(String.format("unit %d voltage", cell + 1))
              .withUnit("mV")
              .build();
      cellProperties.getPropertyFieldList().add(field);
    }
    return cellProperties;
  }

  private void buildAndSendFrame(
      UsbConnection usbConnection,
      int address,
      Map<String, Object> variables,
      CommandProviderProperty property)
      throws IOException {
    String command = property.getCommand();
    Logger.debug("send command {}", command);
    DalyFrame receivedFrame = sendAndValidateFrame(command, address, usbConnection);
    byte[] content = receivedFrame.getContent();
    property.setCachedValue(content);
    byteArrayCalculator.calculate(content, property.getPropertyFieldList(), variables);
  }

  private DalyFrame sendAndValidateFrame(String command, int address, UsbConnection usbConnection)
      throws IOException {
    int id = Integer.parseInt(command, 16);
    DalyFrame sendFrame = new DalyFrame(address, id);
    DalyFrame receivedFrame;
    for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
      protocol.sendData(usbConnection, sendFrame);
      int maxFrames = 1;
      if (id == 0x95) maxFrames = countCells / 3 + 1;
      else if (id == 0x96) maxFrames = countSensors / 7 + 1;
      receivedFrame = protocol.receiveData(usbConnection, maxFrames);
      if (receivedFrame.isValid()) {
        return receivedFrame;
      }
      Logger.warn(
          "attempt {}: Frame is invalid: CRC {} does not match calculated CRC {}",
          attempt,
          receivedFrame.getCrc(),
          receivedFrame.getCalculatedCrc());
      try {
        Thread.sleep(RETRY_DELAY_MILLIS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Retry process was interrupted", e);
      }
    }
    throw new IOException("Failed to receive a valid response for command: " + command);
  }

  private void readNumbersOfCellsAndSensors(UsbConnection usbConnection, Setting setting)
      throws IOException {
    List<PropertyField> counterFields = new ArrayList<>(2);
    PropertyField countCellField = new PropertyField(COUNT_CELLS, FieldType.U8);
    countCellField.setOffset(0);
    countCellField.setLength(1);
    PropertyField countSensorField = new PropertyField(COUNT_SENSORS, FieldType.U8);
    countSensorField.setOffset(1);
    countSensorField.setLength(1);
    counterFields.add(countCellField);
    counterFields.add(countSensorField);
    CommandProviderProperty property = new CommandProviderProperty();
    property.setCommand("94");
    property.setName("0x94");
    property.getPropertyFieldList().addAll(counterFields);
    Map<String, Object> resultMap = new HashMap<>();
    buildAndSendFrame(usbConnection, setting.getProviderAddress(), resultMap, property);
    setting.setConfigurationValue(
        COUNT_CELLS, String.valueOf(resultMap.getOrDefault(COUNT_CELLS, "48")));
    setting.setConfigurationValue(
        COUNT_SENSORS, String.valueOf(resultMap.getOrDefault(COUNT_SENSORS, "16")));
  }

  private int getNumbersOfCells(Setting setting) {
    return Math.min(48, setting.getConfigurationValueAsInteger(COUNT_CELLS, 48));
  }

  private int getNumbersOfSensors(Setting setting) {
    return Math.min(16, setting.getConfigurationValueAsInteger(COUNT_SENSORS, 16));
  }

  private boolean tryToSendFrame(
      UsbConnection usbConnection,
      int providerAddress,
      Map<String, Object> variables,
      CommandProviderProperty property) {
    try {
      buildAndSendFrame(usbConnection, providerAddress, variables, property);
      return true;
    } catch (IOException e) {
      Logger.error(e.getMessage());
      return false;
    }
  }

  @Override
  protected void handleCommandProperty(
      UsbConnection usbConnection, CommandProviderProperty property, Map<String, Object> variables)
      throws IOException, InterruptedException {
    final int providerAddress = providerData.getSetting().getProviderAddress();
    final int sleepDurationMs = providerData.getSetting().getSleepMilliseconds();
    for (int tries = 1; tries <= MAX_RETRIES; tries++) {
      Logger.debug("Attempt {}/{}", tries, MAX_RETRIES);
      if (tryToSendFrame(usbConnection, providerAddress, variables, property)) {
        Logger.debug("Frame successfully sent on attempt {}/{}", tries, MAX_RETRIES);
        return;
      }
      if (tries < MAX_RETRIES) {
        Thread.sleep(sleepDurationMs);
      }
    }
    throw new IOException("Exceeded maximum read errors (" + MAX_RETRIES + "), aborting operation");
  }

  @Override
  protected void handleCachedCommandProperty(
      UsbConnection usbConnection,
      CommandProviderProperty commandProviderProperty,
      Map<String, Object> variables) {
    Logger.debug("use cached result from command '{}'", commandProviderProperty.getName());
    byte[] content = (byte[]) commandProviderProperty.getCachedValue();
    byteArrayCalculator.calculate(
        content, commandProviderProperty.getPropertyFieldList(), variables);
  }
}
