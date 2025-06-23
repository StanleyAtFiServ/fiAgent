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

public class TcpRelay {

    //   public final static String APP_ACK = "<EFTAcknowledgement><AcknowledgementType>0003</AcknowledgementType></EFTAcknowledgement>";
    private final ExecutorService executor;
    private volatile boolean running;
    //   private static final String LISTEN_HOST = "localhost";
    private int listenPort=0;
    private int targetPort=0;

    private byte[] recvBufferEcr = new byte[19200];
    private byte[] sendBufferEcr = new byte[19200];
    private byte[] recvBufferTerm = new byte[19200];
    private byte[] sendBufferTerm = new byte[19200];

    String targetAddr;
    private static Logger logger = AppLogger.getLogger(TcpForwarderOnDm.class);
    private final static int TIMEOUT_95S = 95000; // millisecond
    private final int WAIT_TERM_1ST_TO = 3000;
    private final int WAIT_TERM_RTV_TO = 5000;
    public TcpRelay(String _listenPort, String _targetAddr, String _targetPort)
    {
        listenPort = Integer.parseInt(_listenPort);
        targetAddr = _targetAddr;
        targetPort = Integer.parseInt(_targetPort);

        this.executor = Executors.newCachedThreadPool();
        this.running = true;
    }
    public void start() {

        try {
            // Set the socket to be ready to listen
            ServerSocket listenSocket = new ServerSocket(listenPort);
            
            System.out.println("Listening for connections on port " + listenPort);
            logger.info("Listening for connections on port " + listenPort);
            System.out.println("Target for connections IP " + targetAddr);
            logger.info("Target for connections IP " + targetAddr);
            System.out.println("Target for connections on port " + targetPort);
            logger.info("Target for connections on port " + targetPort);


            while (running) {
                try {
                    final Socket listenEcrSocket = listenSocket.accept();
                    System.out.println("Accepted connection from " + listenEcrSocket.getRemoteSocketAddress());
                    logger.info("Accepted connection from " + listenEcrSocket.getRemoteSocketAddress());

                    executor.submit(new Runnable() {
                        @Override
                        public void run() {
                            relayECRnTerm(listenEcrSocket, targetAddr, targetPort);
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

    private void relayECRnTerm(final Socket _listenEcrSocket, String _targetAddr, int _targetPort) {
        try {
            InetAddress iAddrListen = InetAddress.getByName(_targetAddr);
            final Socket targetTermSocket = new Socket(iAddrListen, _targetPort); // Connect to Target
            System.out.println("Connected to target port " + _targetAddr + ":" + _targetPort);
            logger.info("Connected to target port " + _targetAddr + ":" + _targetPort);

            synchronized (this) {
                notify();
            }

            executor.submit(new Runnable() {
                @Override
                public void run() {
                    ecr2Term(_listenEcrSocket, targetTermSocket);                // Read from ECR and forward to A920
                }
            });
            term2Ecr(targetTermSocket, _listenEcrSocket, 0, 2, WAIT_TERM_1ST_TO);          // Read from A920 and backward to ECR with timeout

        } catch (IOException e) {
            System.err.println("Error handling client: " + e.getMessage());
            logger.log(Level.SEVERE, "Error handling client: " + e.getMessage());
            closeQuietly(_listenEcrSocket);
        }
    }

    private void ecr2Term(Socket source, Socket destination) {


        try {
            InputStream in = source.getInputStream();
            OutputStream out = destination.getOutputStream();

            int bytesRead;
            int bytesSent;

            while ((bytesRead = in.read(recvBufferEcr)) != -1 && running) {
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

                // Move readBuffer with bytesRead from ECR  to Term send Buffer with bytesSend, then write to out(putStream): destination socket.getOutputStream )
                sendBufferTerm = Arrays.copyOf(recvBufferEcr, recvBufferEcr.length);
                bytesSent = bytesRead;
                // Forward data to destination
                out.write(sendBufferTerm, 0, bytesSent);
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

    private void term2Ecr(Socket srcTermSocket, Socket desEcrSocket, int retryCount, int maxRetries, int timeout) {

        try {
            // Set 95-second read timeout
            srcTermSocket.setSoTimeout(timeout);
            InputStream inTerm = srcTermSocket.getInputStream();
            OutputStream outEcr = desEcrSocket.getOutputStream();
            OutputStream outTerm = srcTermSocket.getOutputStream();     //@stan

            int bytesRead;
            int bytesSend;
            String strRead="";
            String orgEcrInMsg;

            try {
                AppMsg appMsgAck = new AppMsg(AppMsg.MSG_ACK, AppMsg.OP_ACK, AppMsg.DATA_ACKNOWLEDGE );
                String stringMsgAck = appMsgAck.packMessage();
                int appMsgAckLen = stringMsgAck.length();

                do {                // wait to receive acknowledge back from A920
                    bytesRead = inTerm.read(recvBufferTerm);
                    if (bytesRead == -1)
                        return; // EOF reached

                    strRead = new String(recvBufferTerm);
                    strRead = strRead.substring(0, appMsgAckLen);

                    if ( strRead.compareTo(stringMsgAck) != 0 ) {
                        // Received Term data
                        // then send Application xml ACKNOWLEDGE back to Term
                        AppMsg appMsgAckTerm = new AppMsg(AppMsg.MSG_ACK, AppMsg.OP_ACK, AppMsg.DATA_ACKNOWLEDGE);         //@stan
                        byte[] ackTermBytes = appMsgAckTerm.packMessage().getBytes(StandardCharsets.UTF_8);       //@stan
                        outTerm.write(ackTermBytes);   // app. ack back directly, doesn't use sendBuffer                                                           //@stan
                        outTerm.flush();                                                                            //@stan
                        System.out.println("Relay xml ack back to Term");                                           //@stan
                        logger.info("Relay xml ack back to Term");                                              //@stan
                        // then route the Ecr data in system to Term
                        sendBufferEcr = Arrays.copyOf(recvBufferTerm, recvBufferTerm.length );  // read the data of term has been reached system in early stage.
                        bytesSend = bytesRead;
                        outEcr.write(sendBufferEcr, 0, bytesSend);                // now send to ECR
                        outEcr.flush();
                        System.out.println("Backwarded " + bytesRead + " bytes from " +
                                srcTermSocket.getLocalPort() + " to " + desEcrSocket.getPort());
                        logger.info("Backwarded " + bytesSend + " bytes from " +
                                srcTermSocket.getLocalPort() + " to " + desEcrSocket.getPort());

                        // Continue processing if more data is available immediately
                        if (inTerm.available() > 0) {
                            term2Ecr(srcTermSocket, desEcrSocket, 0, maxRetries, WAIT_TERM_1ST_TO );
                        }
                    }
                } while ( strRead.compareTo(stringMsgAck) == 0 );

            } catch (SocketTimeoutException e) {
                if (retryCount < maxRetries) {
                    // Send "Retry" message on timeout
                    try {
                        String orgMsg = new String(sendBufferTerm, "UTF-8");
                        AppMsg rtvAppMsg = new AppMsg(AppMsg.MSG_RTV, AppMsg.OP_QST, orgMsg);         //@stan
                        String message2term = rtvAppMsg.packMessage();
                        byte[] retrievalBytes  = message2term.getBytes(StandardCharsets.UTF_8);       //@stan

                        byte[] messageBytes = Arrays.copyOf(retrievalBytes, retrievalBytes.length);

                        outTerm.write(messageBytes);
                        outTerm.flush();
                        System.out.println("Sent 'Retry' message due to timeout");
                        logger.info("Sent 'Retry' message: " + messageBytes);

                        // Recursively restart term2Ecr with incremented retry count
                        term2Ecr(srcTermSocket, desEcrSocket, retryCount + 1, maxRetries, WAIT_TERM_RTV_TO);
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