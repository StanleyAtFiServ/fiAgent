package org.fiserv;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.util.Arrays;
import java.util.Scanner;
import java.util.logging.*;

public class TcpForwarderPerm {
    private volatile boolean running;
    int listenPort = 0;
    int targetPort = 0;
    private byte[] recvBuffer = new byte[19200];
    private byte[] sendBuffer = new byte[19200];
    String strIpAddress, strTargetPort, strListenPort;
    private static Logger logger = AppLogger.getLogger(TcpForwarderPerm.class);
    private final static int TIMEOUT_95S = 95000;
    private Socket targetSocket;
    private ServerSocket listenSocket;

    public TcpForwarderPerm(String _listenPort, String _targetAddr, String _targetPort)
    {
        strListenPort = _listenPort;
        strIpAddress = _targetAddr;
        strTargetPort = _targetPort;
        this.running = true;
    }

    public void start() {
        try {

/*
            System.out.println("1. TCP Forwarder");
            System.out.println("----------------");
            Scanner scanner = new Scanner(System.in);

            // Get listening port
            System.out.println("Enter local listening port : ");
            strListenPort = scanner.nextLine().trim();
            while (true) {
                try {
                    listenPort = Integer.parseInt(strListenPort);
                    if (listenPort < 0 || listenPort > 65535) {
                        System.out.println("Port number must be between 0 and 65535. Please try again.");
                        System.out.print("Please enter the port number: ");
                        strListenPort = scanner.nextLine().trim();
                    } else {
                        break;
                    }
                } catch (NumberFormatException e) {
                    System.out.println("Invalid port number. Please enter a numeric value.");
                    System.out.print("Please enter the port number: ");
                    strListenPort = scanner.nextLine().trim();
                }
            }
*/
            // Create server socket
            listenPort = Integer.parseInt(strListenPort);
            listenSocket = new ServerSocket(listenPort);
            System.out.println("Listening for connections on port " + strListenPort);
            logger.info("Listening for connections on port " + strListenPort);
            System.out.println("Target address" + strIpAddress);
            logger.info("Target address " + strIpAddress);
            targetPort = Integer.parseInt(strTargetPort);
            System.out.println("Target port" + strTargetPort);
            logger.info("Target port " + strTargetPort);

            // Establish permanent target connection
//            establishTargetConnection();

            while (running) {
                try {
                    final Socket clientSocket = listenSocket.accept();
                    System.out.println("Accepted connection from " + clientSocket.getRemoteSocketAddress());
                    logger.info("Accepted connection from " + clientSocket.getRemoteSocketAddress());

                    // Handle client using permanent target connection
                    handleClient(clientSocket);
                } catch (IOException e) {
                    if (running) {
                        System.err.println("Error accepting connection: " + e.getMessage());
                        logger.log(Level.SEVERE, "Error accepting connection: " + e.getMessage(), e);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to start server: " + e.getMessage());
            logger.log(Level.SEVERE, "Failed to start server: " + e.getMessage());
        } finally {
            closeQuietly(targetSocket);
            closeServerSocket(listenSocket);
        }
    }

    private void establishTargetConnection() throws IOException {
        closeQuietly(targetSocket);
        targetSocket = new Socket(strIpAddress, targetPort);
        targetSocket.setKeepAlive(true);
        System.out.println("Permanent connection established to target " + strIpAddress + ":" + targetPort);
        logger.info("Permanent connection established to target " + strIpAddress + ":" + targetPort);
    }

    private void handleClient(final Socket clientSocket) {
        // Reconnect if target connection is broken
        if (targetSocket == null || targetSocket.isClosed() || !targetSocket.isConnected()) {
            try {
                establishTargetConnection();
            } catch (IOException e) {
                System.err.println("Failed to reconnect to target: " + e.getMessage());
                logger.log(Level.SEVERE, "Failed to reconnect to target", e);
                closeQuietly(clientSocket);
                return;
            }
        }

        // Start data forwarding threads
        Thread forwardThread = new Thread(new Runnable() {
            @Override
            public void run() {
                forwardData(clientSocket, targetSocket);
            }
        });

        Thread backwardThread = new Thread(new Runnable() {
            @Override
            public void run() {
                backwardData(targetSocket, clientSocket, 0, 2);
            }
        });

        forwardThread.start();
        backwardThread.start();

        // Wait for threads to finish
        try {
            forwardThread.join();
            backwardThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.log(Level.SEVERE, "Thread interrupted", e);
        } finally {
            closeQuietly(clientSocket);
        }
    }

    private void forwardData(Socket source, Socket destination) {
        try {
            InputStream in = source.getInputStream();
            OutputStream out = destination.getOutputStream();

            byte[] recvBuffer = new byte[8192];
            int bytesRead;
            int bytesSent;

            while ((bytesRead = in.read(recvBuffer)) != -1 && running) {
                // Send acknowledgment to source
                try {
                    OutputStream sourceOut = source.getOutputStream();
                    AppMsg appMsgAck = new AppMsg(AppMsg.MSG_ACK, AppMsg.OP_ACK, AppMsg.DATA_ACKNOWLEDGE);
                    byte[] ackBytes = appMsgAck.packMessage().getBytes("UTF-8");
                    sourceOut.write(ackBytes);
                    sourceOut.flush();
                    System.out.println("Sent acknowledgment to source");
                    logger.info("Sent acknowledgment to source");
                } catch (IOException ackError) {
                    System.err.println("Failed to send acknowledgment: " + ackError.getMessage());
                    logger.log(Level.SEVERE, "Failed to send acknowledgment", ackError);
                }

                // Forward data to destination
                sendBuffer = Arrays.copyOf(recvBuffer, recvBuffer.length);
                bytesSent = bytesRead;
                out.write(sendBuffer, 0, bytesSent);
                out.flush();
                System.out.println("Forwarded " + bytesRead + " bytes from " +
                        source.getLocalPort() + " to " + destination.getPort());
                logger.info("Forwarded " + bytesSent + " bytes from " +
                        source.getLocalPort() + " to " + destination.getPort());
            }
        } catch (IOException e) {
            if (running) {
                System.err.println("Forwarding error: " + e.getMessage());
                logger.log(Level.SEVERE, "Forwarding error: " + e.getMessage());
            }
        } finally {
            closeQuietly(source);
        }
    }

    private void backwardData(Socket source, Socket destination, int retryCount, int maxRetries) {
        try {
            // Set 95-second read timeout
            source.setSoTimeout(5000);
            InputStream in = source.getInputStream();
            OutputStream out = destination.getOutputStream();

            int bytesRead;
            int bytesSend;
            String strRead = "";

            try {
                AppMsg appMsgAck = new AppMsg(AppMsg.MSG_ACK, AppMsg.OP_ACK, AppMsg.DATA_ACKNOWLEDGE);
                String stringMsgAck = appMsgAck.packMessage();
                int appMsgAckLen = stringMsgAck.length();

                do {
                    // wait to receive acknowledge back from A920
                    bytesRead = in.read(recvBuffer);
                    if (bytesRead == -1)
                        return; // EOF reached

                    strRead = new String(recvBuffer);
                    strRead = strRead.substring(0, appMsgAckLen);

                    if (strRead.compareTo(stringMsgAck) != 0) {

                        sendBuffer = Arrays.copyOf(recvBuffer, recvBuffer.length);
                        bytesSend = bytesRead;
                        // route the message from ECR in buffer and out to A920
                        out.write(sendBuffer, 0, bytesSend);
                        out.flush();
                        System.out.println("Backwarded " + bytesRead + " bytes from " +
                                source.getLocalPort() + " to " + destination.getPort());
                        logger.info("Backwarded " + bytesSend + " bytes from " +
                                source.getLocalPort() + " to " + destination.getPort());

                        // Continue processing if more data is available immediately
                        if (in.available() > 0) {
                            backwardData(source, destination, 0, maxRetries);
                        }
                    }
                } while (strRead.compareTo(stringMsgAck) == 0);

            } catch (SocketTimeoutException e) {
                if (retryCount < maxRetries) {
                    // Send "Retry" message on timeout
                    try {

                        OutputStream sourceOut = source.getOutputStream(); // set source from in stream to out stream

                        //byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
                        byte[] messageBytes = Arrays.copyOf(sendBuffer, sendBuffer.length);

                        sourceOut.write(messageBytes);
                        sourceOut.flush();
                        System.out.println("Sent 'Retry' message due to timeout");
                        logger.info("Sent 'Retry' message: " + messageBytes);

                        // Recursively restart backwardData with incremented retry count
                        backwardData(source, destination, retryCount + 1, maxRetries);
                    } catch (IOException sendError) {
                        System.err.println("Failed to send 'Retry': " + sendError.getMessage());
                        logger.log(Level.SEVERE, "Failed to send 'Retry'", sendError);
                    }
                } else {
                    System.err.println("Maximum retry limit reached, stopping retry.");
                    logger.warning("Maximum retry limit reached, stopping retry.");
                }
            }
        } catch (IOException e) {
            if (running) {
                System.err.println("Backwarded error: " + e.getMessage());
                logger.log(Level.SEVERE, "Backwarded error: " + e.getMessage());
            }
        }
    }

    private static boolean isValidIpAddress(String ip) {
        try {
            // This validates both IPv4 and IPv6 addresses
            InetAddress.getByName(ip);
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    // Handles both Socket and ServerSocket closure
    private void closeQuietly(Socket socket) {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            // Ignore
        }
    }

    private void closeServerSocket(ServerSocket serverSocket) {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            // Ignore
        }
    }

    public void stop() {
        running = false;
        closeQuietly(targetSocket);
        closeServerSocket(listenSocket);
    }
}