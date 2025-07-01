package org.fiserv;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Scanner;
import java.util.logging.*;

public class TcpForwarderOnDm {

 //   public final static String APP_ACK = "<EFTAcknowledgement><AcknowledgementType>0003</AcknowledgementType></EFTAcknowledgement>";
    private final ExecutorService executor;
    private volatile boolean running;
 //   private static final String LISTEN_HOST = "localhost";
    int listenPort=0;
    int targetPort=0;

    private byte[] recvBuffer = new byte[19200];
    private byte[] sendBuffer = new byte[19200];
    String strIpAddress,strTargetPort,strListenPort;
    private static Logger logger = AppLogger.getLogger(TcpForwarderOnDm.class);
    private final static int TIMEOUT_95S = 95000; // millisecond

    public TcpForwarderOnDm() {
        this.executor = Executors.newCachedThreadPool();
        this.running = true;
    }
    public void start() {

        try {
            // Prompt header
            System.out.println("1. TCP Forwarder");
            System.out.println("----------------");
            Scanner scanner = new Scanner(System.in);
            //--------------Prompt for local tcp port------------------
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

            // Set the socket to be ready to listen
            ServerSocket listenSocket = new ServerSocket(listenPort);
            System.out.println("Listening for connections on port " + listenPort);
            logger.info("Listening for connections on port " + listenPort);

            //----------------Prompt for IP address-----------------------

            System.out.println("Enter target IP address: ");
            strIpAddress = scanner.nextLine().trim();
            // Validate IP address format
            while (!isValidIpAddress(strIpAddress)) {
                System.out.println("Invalid IP address format. Please try again.");
                System.out.print("Please enter the IP address: ");
                strIpAddress = scanner.nextLine().trim();
            }
            logger.info("Target address " + strIpAddress);

            // Prompt for Port Number
            System.out.println("Enter the port number: ");
            strTargetPort = scanner.nextLine().trim();

            while (true) {
                try {
                    targetPort = Integer.parseInt(strTargetPort);
                    if (targetPort < 0 || targetPort > 65535) {
                        System.out.println("Port number must be between 0 and 65535. Please try again.");
                        System.out.print("Please enter the port number: ");
                        strTargetPort = scanner.nextLine().trim();
                    } else {
                        break;
                    }
                } catch (NumberFormatException e) {
                    System.out.println("Invalid port number. Please enter a numeric value.");
                    System.out.print("Please enter the port number: ");
                    strTargetPort = scanner.nextLine().trim();
                }
            }
            logger.info("Target port " + targetPort);

            while (running) {
                try {
                    final Socket clientSocket = listenSocket.accept();
                    System.out.println("Accepted connection from " + clientSocket.getRemoteSocketAddress());
                    logger.info("Accepted connection from " + clientSocket.getRemoteSocketAddress());

                    final String ip = strIpAddress;
                    final String port = strTargetPort;

                    executor.submit(new Runnable() {
                        @Override
                        public void run() {
                            onDemandHandleClient(clientSocket, ip, port);
                        }
                    });
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
            executor.shutdown();
        }
    }

    public void stop() {
        running = false;
        executor.shutdownNow();
    }

    private void onDemandHandleClient(final Socket clientSocket, String _strIpAddress, String _strTargetPort) {
        try {
            InetAddress iAddrListen = InetAddress.getByName(_strIpAddress);
            final Socket targetSocket = new Socket(iAddrListen, Integer.parseInt(_strTargetPort)); // Connect to Target
            System.out.println("Connected to target port " + _strIpAddress + ":" + _strTargetPort);
            logger.info("Connected to target port " + _strIpAddress + ":" + _strTargetPort);

            synchronized (this) {
                notify();
            }

            executor.submit(new Runnable() {
                @Override
                public void run() {
                    forwardData(clientSocket, targetSocket);                // Read from ECR and forward to A920
                }
            });
            backwardData(targetSocket, clientSocket, 0, 2);          // Read from A920 and backward to ECR with timeout

        } catch (IOException e) {
            System.err.println("Error handling client: " + e.getMessage());
            logger.log(Level.SEVERE, "Error handling client: " + e.getMessage());
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
                // Send "acknowledge" back to source before forwarding data
                try {
                    OutputStream sourceOut = source.getOutputStream();
                    AppMsg appMsgAck = new AppMsg(AppMsg.MSG_ACK, AppMsg.OP_ACK, AppMsg.DATA_ACKNOWLEDGE);
                    byte[] ackBytes = appMsgAck.packMessage().getBytes(StandardCharsets.UTF_8);
                    sourceOut.write(ackBytes);
                    sourceOut.flush();
                    System.out.println("Sent acknowledgment to source");
                    logger.info("Sent acknowledgment to source");
                } catch (IOException ackError) {
                    System.err.println("Failed to send acknowledgment: " + ackError.getMessage());
                    logger.log(Level.SEVERE, "Failed to send acknowledgment", ackError);
                }

                // Move readBuffer with bytesRead from ECR  to sendBuffer with bytesSend, then write to out(putStream): destination socket.getOutputStream )
                sendBuffer = Arrays.copyOf(recvBuffer, recvBuffer.length);
                bytesSent = bytesRead;
                // Forward data to destination
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
            closeQuietly(destination);
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
            String strRead="";

            try {
                AppMsg appMsgAck = new AppMsg(AppMsg.MSG_ACK, AppMsg.OP_ACK, AppMsg.DATA_ACKNOWLEDGE);
                String stringMsgAck = appMsgAck.packMessage();
                int appMsgAckLen = stringMsgAck.length();

                do {                // wait to receive acknowledge back from A920
                    bytesRead = in.read(recvBuffer);
                    if (bytesRead == -1)
                        return; // EOF reached

                    strRead = new String(recvBuffer);
                    strRead = strRead.substring(0, appMsgAckLen);

                    if ( strRead.compareTo(stringMsgAck) != 0 ) {

                        sendBuffer = Arrays.copyOf(recvBuffer, recvBuffer.length );
                        bytesSend = bytesRead;
                        out.write(sendBuffer, 0, bytesSend);                // route the message from ECR in buffer and out to A920
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
                } while ( strRead.compareTo(stringMsgAck) == 0 );

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

    // Helper method to validate IP address format
    private static boolean isValidIpAddress(String ip) {
        try {
            // This validates both IPv4 and IPv6 addresses
            InetAddress.getByName(ip);
            return true;
        } catch (UnknownHostException e) {
            return false;
        }

        // Alternative for IPv4-only validation:
        /*
        String ipv4Pattern = "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$";
        return ip.matches(ipv4Pattern);
        */
    }
    private void closeQuietly(Socket socket) {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            // Ignore
        }
    }
}