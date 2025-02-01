package org.carecode.mw.lims.mw.humaCount5D;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.carecode.lims.libraries.*;

public class HumaCount5DServer {

    private static final Logger logger = LogManager.getLogger(HumaCount5DServer.class);
    private static final char VT = 0x0B;  // Start Block (HL7 MLLP)
    private static final char FS = 0x1C;  // End Block (HL7 MLLP)
    private static final char CR = 0x0D;  // Carriage Return (HL7 Terminator)
    private static final int MAX_UNEXPECTED_DATA = 1000;

    private static MiddlewareSettings middlewareSettings;

    private static ServerSocket serverSocket;
    private static int port;
    private DataBundle patientDataBundle = new DataBundle();
    private boolean receivingQuery, respondingQuery;
    private static int frameNumber;

    public void start(int port) {
        this.port = SettingsLoader.getSettings().getAnalyzerDetails().getAnalyzerPort();
        try {
            serverSocket = new ServerSocket(this.port);
            logger.info("Server started on port " + this.port);
            while (true) {
                try (Socket clientSocket = serverSocket.accept()) {
                    logger.info("Client connected: " + clientSocket.getInetAddress().getHostAddress());
                    handleClient(clientSocket);
                } catch (IOException e) {
                    logger.error("Error handling client connection", e);
                }
            }
        } catch (IOException e) {
            logger.error("Error starting server on port " + this.port, e);
        }
    }

    public void stop() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
                logger.info("Server stopped.");
            }
        } catch (IOException e) {
            logger.error("Error stopping server", e);
        }
    }

    private void handleClient(Socket clientSocket) {
        try (InputStream in = new BufferedInputStream(clientSocket.getInputStream()); OutputStream out = new BufferedOutputStream(clientSocket.getOutputStream())) {

            while (true) {
                String message = readHL7Message(in);
                if (message == null) {
                    break;
                }
                logger.debug("Received HL7 Message: " + message);
                processHL7Message(message);
            }
        } catch (IOException e) {
            logger.error("Error during client communication", e);
        }
    }

    public void processHL7Message(String hl7Message) {
        LISCommunicator lisCommunicator = new LISCommunicator();
        logger.info("Processing HL7 message from Mindray BC 5150 Analyzer");
        String sampleId = "";

        BufferedReader reader = new BufferedReader(new StringReader(hl7Message));
        String line;
        try {
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                logger.info("Received data: " + line);

                if (line.startsWith("OBR|")) {
                    String[] parts = line.split("\\|");
                    if (parts.length > 3) {
                        sampleId = parts[3].trim();
                        logger.info("Sample ID extracted: " + sampleId);
                    }
                } else if (line.startsWith("OBX|")) {
                    String[] parts = line.split("\\|");
                    if (parts.length > 6) {
                        String[] testDetails = parts[3].split("\\^");
                        String testCode = testDetails.length > 1 ? testDetails[1].trim() : testDetails[0].trim();
                        String resultValueString = parts[5].trim();
                        String resultUnits = parts[6].trim();
                        String resultDateTime = "";  // Set as needed.

                        // Use the library constructor:
                        // ResultsRecord(String testCode, String resultValueString, String resultUnits, String resultDateTime, String instrumentName, String sampleId)
                        ResultsRecord rr = new ResultsRecord(testCode, resultValueString, resultUnits, resultDateTime, "MindRayBC5150", sampleId);

                        DataBundle db = new DataBundle();
                        db.setMiddlewareSettings(middlewareSettings);
                        db.getResultsRecords().add(rr);
                        LISCommunicator.pushResults(db);

                        logger.info("Test Code: " + testCode);
                        logger.info("Result Value: " + resultValueString);
                        logger.info("Result Units: " + resultUnits);
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Error processing HL7 message", e);
        }
    }

    public static MiddlewareSettings getMiddlewareSettings() {
        if (middlewareSettings == null) {
            SettingsLoader sl = new SettingsLoader();
            middlewareSettings = sl.getSettings();
        }
        return middlewareSettings;
    }

    private String readHL7Message(InputStream in) throws IOException {
        StringBuilder message = new StringBuilder();
        int data;
        boolean reading = false;
        while ((data = in.read()) != -1) {
            if (data == VT) {
                reading = true;
            }
            if (reading) {
                message.append((char) data);
            }
            if (data == FS) {
                break;
            }
        }
        return message.length() > 0 ? message.toString() : null;
    }

    private void processHL7Message(String message, OutputStream out) throws IOException {
        if (message.contains("ORU^R01")) {
            logger.debug("Processing Results Message");
            processResultsMessage(message);
            sendACK(out);
        } else if (message.contains("ORM^O01")) {
            logger.debug("Processing Order Query");
            processQueryMessage(message);
            sendACK(out);
        } else {
            logger.warn("Unknown HL7 Message Type");
            sendNAK(out);
        }
    }

    private void processResultsMessage(String message) {
        ResultsRecord result = parseResultsRecord(message);
        getPatientDataBundle().getResultsRecords().add(result);
        logger.debug("Result processed: " + result);
    }

    private void processQueryMessage(String message) {
        QueryRecord query = parseQueryRecord(message);
        getPatientDataBundle().getQueryRecords().add(query);
        logger.debug("Query processed: " + query);
    }

    private void sendACK(OutputStream out) throws IOException {
        String ackMessage = VT + "MSH|^~\\&|LIS|HumaCount5D|||" + getTimestamp() + "||ACK^R01|12345|P|2.3.1" + CR
                + "MSA|AA|12345" + FS + CR;
        out.write(ackMessage.getBytes());
        out.flush();
    }

    private void sendNAK(OutputStream out) throws IOException {
        String nakMessage = VT + "MSH|^~\\&|LIS|HumaCount5D|||" + getTimestamp() + "||ACK^R01|12345|P|2.3.1" + CR
                + "MSA|AE|12345" + FS + CR;
        out.write(nakMessage.getBytes());
        out.flush();
    }

    private String getTimestamp() {
        return new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
    }

    public static ResultsRecord parseResultsRecord(String hl7Message) {
        String[] fields = hl7Message.split("\\|");
        String sampleId = fields[2];
        String testCode = fields[3];
        String resultValue = fields[4];
        String unit = fields[5];
        String resultDateTime = fields[6];
        return new ResultsRecord(1, testCode, resultValue, unit, resultDateTime, "HumaCount5D", sampleId);
    }

    public static QueryRecord parseQueryRecord(String hl7Message) {
        String[] fields = hl7Message.split("\\|");
        String sampleId = fields[2];
        return new QueryRecord(0, sampleId, "", "");
    }

    public DataBundle getPatientDataBundle() {
        if (patientDataBundle == null) {
            patientDataBundle = new DataBundle();
        }
        return patientDataBundle;
    }
}
