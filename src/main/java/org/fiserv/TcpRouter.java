package org.fiserv;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TcpRouter {

    //   public final static String APP_ACK = "<EFTAcknowledgement><AcknowledgementType>0003</AcknowledgementType></EFTAcknowledgement>";
    private final ExecutorService executor;
    private volatile boolean running;
    //   private static final String LISTEN_HOST = "localhost";
    private int listenPort=0;
    private int targetPort=0;
    String targetAddr;
    private static Logger logger = AppLogger.getLogger(TcpRouter.class);
    private final int WAIT_TERM_1ST_TO = 15000;
    private final int WAIT_TERM_RTV_TO = 5000;

    private String stringMsgAck;
    private int appMsgAckLen;
    private AppMsg storeAppMsg;
    public TcpRouter(String _listenPort, String _targetAddr, String _targetPort)
    {
        listenPort = Integer.parseInt(_listenPort);
        targetAddr = _targetAddr;
        targetPort = Integer.parseInt(_targetPort);
        storeAppMsg = new AppMsg();
        this.executor = Executors.newCachedThreadPool();
        this.running = true;
    }
    public void start() {

        try {
            // Set the socket to be ready to listen
            ServerSocket listenSocket = new ServerSocket(listenPort);
            
            System.out.println("Listening for connections on port " + listenPort);
            logger.info("Listening for connections on port " + listenPort);
            System.out.println("Target for connections IP " + targetAddr + "and Port " + targetPort);
            logger.info("Target for connections IP " + targetAddr + "and Port " + targetPort);

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
            ConsoleLogger("Executor shutdown");
            executor.shutdown();
        }
    }

    public void stop() {
        running = false;
        executor.shutdownNow();
    }

    private void relayECRnTerm(final Socket _listenEcrSocket, String _targetAddr, int _targetPort)
    {
        try {
            InetAddress iAddrListen = InetAddress.getByName(_targetAddr);
            final Socket targetTermSocket = new Socket(iAddrListen, _targetPort); // Connect to Target
            System.out.println("Connected to target port " + _targetAddr + ":" + _targetPort);
            logger.info("Connected to target port " + _targetAddr + ":" + _targetPort);

            synchronized (this) {
                notify();
            }
            /*
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    ecr2Term(_listenEcrSocket, targetTermSocket);                // Read from ECR and forward to A920
                }
            });
             */
            ecr2Term(_listenEcrSocket, targetTermSocket);
            term2Ecr(targetTermSocket, _listenEcrSocket, 0, 3, WAIT_TERM_1ST_TO);          // Read from A920 and backward to ECR with timeout
            ConsoleLogger("waiting for last ACK from ECR!!!!");
            ackWaitFromReceiver(_listenEcrSocket);
        } catch (IOException e) {
            System.err.println("Error handling client: " + e.getMessage());
            logger.log(Level.SEVERE, "Error handling client: " + e.getMessage());
            closeQuietly(_listenEcrSocket);
        }
    }

    private void ecr2Term(Socket sckSrcEcr, Socket sckDesTerm) {

        byte[] bytesForRecvEcr = new byte[19200];
        byte[] bytesForSendTerm;

        try {
            InputStream in = sckSrcEcr.getInputStream();
            OutputStream out = sckDesTerm.getOutputStream();

            int bytesRead;
            int bytesSent;

            while ((bytesRead = in.read(bytesForRecvEcr)) != -1 && running) {   // 1. Wait for data
                try {
                    ConsoleLogger("****RECV-ECR*****");
                    ackBackSender(sckSrcEcr);       // 2. Send "acknowledge" back to source before forwarding data
                } catch (IOException ackError) {
                    System.err.println("Failed to send acknowledgment: " + ackError.getMessage());
                    logger.log(Level.SEVERE, "Failed to send acknowledgment", ackError);
                }
                bytesForSendTerm = Arrays.copyOf(bytesForRecvEcr, bytesForRecvEcr.length); // 3. forward from ecr to term
                bytesSent = bytesRead;
                out.write(bytesForSendTerm, 0, bytesSent);  // Forward data to destination
                out.flush();
                storeAppMsg.saveMsg(bytesForSendTerm);

                System.out.println("Forwarded " + bytesRead + " bytes from "
                        + sckSrcEcr.getLocalPort() + " to " + sckDesTerm.getPort());
                logger.info("Forwarded " + bytesSent + " bytes from "
                        + sckSrcEcr.getLocalPort() + " to " + sckDesTerm.getPort());

                if ( ackWaitFromReceiver(sckDesTerm) )
                    return;
                else
                    throw new IOException("Incorrect ACK message received");

            }
        } catch (IOException e) {
            if (running) {
                System.err.println("Forwarding error: " + e.getMessage());
                logger.log(Level.SEVERE, "Forwarding error: " + e.getMessage());
            }
        }
    }

    private void term2Ecr(Socket srcTermSocket, Socket desEcrSocket, int retryCount, int maxRetries, int timeout) {

        byte[] bytesBackRecvTerm = new byte[19200];
        byte[] bytesBackSendEcr;

        try {
            // Set 95-second read timeout
            srcTermSocket.setSoTimeout(timeout);
            InputStream inTerm = srcTermSocket.getInputStream();
            OutputStream outEcr = desEcrSocket.getOutputStream();
            OutputStream outTerm = srcTermSocket.getOutputStream();     //@stan

            int bytesRead;
            int bytesSend;
            String strReadTerm,strReadEcr;

            try {

                while ((bytesRead = inTerm.read(bytesBackRecvTerm)) != -1 && running) {

                    ackBackSender(srcTermSocket);
                    closeQuietly(srcTermSocket);

                    bytesBackSendEcr = Arrays.copyOf(bytesBackRecvTerm, bytesBackRecvTerm.length);  // read the data of term has been reached system in early stage.
                    bytesSend = bytesRead;
                    outEcr.write(bytesBackSendEcr, 0, bytesSend);                // now send to ECR
                    outEcr.flush();
                    ConsoleLogger("Backwarded : " + bytesRead + " bytes from " +
                                srcTermSocket.getLocalPort() + " to " + desEcrSocket.getPort());
                }

            } catch (SocketTimeoutException e) {
                if (retryCount < maxRetries) {
                    // Send "Retry" message on timeout
                    try {
                        String orgMsg = new String(storeAppMsg.getMsg(), "UTF-8");   //Get stored message
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
        } finally {
            closeQuietly(srcTermSocket);
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

    private void ackBackSender(Socket socketAckBackSender) throws IOException
    {
        int ack2Port = socketAckBackSender.getPort();

        OutputStream streamOut = socketAckBackSender.getOutputStream();
        AppMsg appMsgAck = new AppMsg(AppMsg.MSG_ACK, AppMsg.OP_ACK, AppMsg.DATA_ACKNOWLEDGE);
        byte[] ackBytes = appMsgAck.packMessage().getBytes(StandardCharsets.UTF_8);

        streamOut.write(ackBytes);
        streamOut.flush();
        System.out.println("Sent acknowledgment to sender : " + ack2Port );
        logger.info("Sent acknowledgment to source " + ack2Port);
    }

    private boolean ackWaitFromReceiver (Socket socketWaitFromRecv) throws IOException
    {

        int wait4Port = socketWaitFromRecv.getPort();
        InputStream inAck = socketWaitFromRecv.getInputStream();
        int bytesReadAck;

        AppMsg appMsgAck = new AppMsg(AppMsg.MSG_ACK, AppMsg.OP_ACK, AppMsg.DATA_ACKNOWLEDGE);
        String sampleMsgAck = appMsgAck.packMessage();
        String recvMsgAck;
        byte[] bytesMsgAck = new byte[1024];

        while ((bytesReadAck = inAck.read(bytesMsgAck)) != -1 && running) {   // wait for data

            recvMsgAck = new String(bytesMsgAck, "UTF-8");
            recvMsgAck = recvMsgAck.substring(0, sampleMsgAck.length());

            System.out.println("Supposed ACK Message at port " + wait4Port + " now receive is " + recvMsgAck );

            if (sampleMsgAck.compareTo(recvMsgAck) == 0)
                return true;
            else
                return false;
        }
        return false;
    }

    private void ConsoleLogger (String logMsg )
    {
        System.out.println(logMsg );
        logger.info(logMsg);
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