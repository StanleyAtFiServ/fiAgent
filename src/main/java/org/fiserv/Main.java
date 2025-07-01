package org.fiserv;
import java.util.Scanner;

public class Main {
    private static final int LISTEN_PORT = 30186;
    private static final int TARGET_PORT = 30187;

    public static void main(String[] args) {

        String localPort;
        String targetAddr;
        String targetPort;

        localPort = args[0];
        targetAddr = args[1];
        targetPort = args[2];

        Scanner scanner = new Scanner(System.in);
        int choice;

        do {
            System.out.println("-------FiRelay------");
            System.out.println("Select Option (0-1)");
            System.out.println("1. TCP Relay");
            System.out.println("0. Exit");

            while (!scanner.hasNextInt()) {
                System.out.println("Invalid input, Enter again");
                scanner.next();
            }
            choice = scanner.nextInt();

            switch (choice) {
                case 1:
                    System.out.println("TCP Relay");
//                    TcpForwarderPerm tcpForwarder = new TcpForwarderPerm(localPort, targetAddr, targetPort );
//                    tcpForwarder.start();
                    TcpRouter tcpRelay = new TcpRouter(localPort, targetAddr, targetPort );
                    tcpRelay.start();
                    break;
                case 0:
                    break;
                default:
                    System.out.println("Invalid input, enter again");
            }
            System.out.println();
        } while (choice != 0);

        scanner.close();
    }
}
    /*
    private static final int LISTEN_PORT = 30186;
    private static final int TARGET_PORT = 30187;
    private static final ExecutorService executor = Executors.newCachedThreadPool();


    public static void main(String[] args) {
        try {
            // Start the listening server
            SimpleTCPForwarder simpleTCPForwarder = new SimpleTCPForwarder();

            ServerSocket listenSocket = new ServerSocket(LISTEN_PORT);
            System.out.println("Listening for connections on port " + LISTEN_PORT);

            // Start the active connector
            System.out.println("Will connect to localhost:" + TARGET_PORT);
            executor.submit(() -> simpleTCPForwarder.connectToTarget());

            // Accept incoming connections
            while (true) {
                Socket clientSocket = listenSocket.accept();
                System.out.println("Accepted connection from " + clientSocket.getRemoteSocketAddress());

                executor.submit(() -> simpleTCPForwarder.handleClient(clientSocket));
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }

     */
