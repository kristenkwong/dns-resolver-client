package ca.ubc.cs.cs317.dnslookup;

import java.io.Console;
import java.net.DatagramPacket;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;

public class DNSLookupService {

    private static final int DEFAULT_DNS_PORT = 53;
    private static final int MAX_INDIRECTION_LEVEL = 10;

    private static InetAddress rootServer;
    private static boolean verboseTracing = false;
    private static DatagramSocket socket;

    private static DNSCache cache = DNSCache.getInstance();

    private static Random random = new Random();
    final static int MAX_QUERY_ID = 0xFFFF; // must be 16 bit -> 0x0 to 0xFFFF

    private static int[] generatedIDs = new int[65536]; // can have 65536 unique values
    // if the ID was generated, generatedIDs[ID#] will be 1, otherwise 0

    /**
     * Main function, called when program is first invoked.
     *
     * @param args list of arguments specified in the command line.
     */
    public static void main(String[] args) {

        if (args.length != 1) {
            System.err.println("Invalid call. Usage:");
            System.err.println("\tjava -jar DNSLookupService.jar rootServer");
            System.err.println("where rootServer is the IP address (in dotted form) of the root DNS server to start the search at.");
            System.exit(1);
        }

        try {
            rootServer = InetAddress.getByName(args[0]);
            System.out.println("Root DNS server is: " + rootServer.getHostAddress());
        } catch (UnknownHostException e) {
            System.err.println("Invalid root server (" + e.getMessage() + ").");
            System.exit(1);
        }

        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(5000);
        } catch (SocketException ex) {
            ex.printStackTrace();
            System.exit(1);
        }

        Scanner in = new Scanner(System.in);
        Console console = System.console();
        do {
            // Use console if one is available, or standard input if not.
            String commandLine;
            if (console != null) {
                System.out.print("DNSLOOKUP> ");
                commandLine = console.readLine();
            } else
                try {
                    commandLine = in.nextLine();
                } catch (NoSuchElementException ex) {
                    break;
                }
            // If reached end-of-file, leave
            if (commandLine == null) break;

            // Ignore leading/trailing spaces and anything beyond a comment character
            commandLine = commandLine.trim().split("#", 2)[0];

            // If no command shown, skip to next command
            if (commandLine.trim().isEmpty()) continue;

            String[] commandArgs = commandLine.split(" ");

            if (commandArgs[0].equalsIgnoreCase("quit") ||
                    commandArgs[0].equalsIgnoreCase("exit"))
                break;
            else if (commandArgs[0].equalsIgnoreCase("server")) {
                // SERVER: Change root nameserver
                if (commandArgs.length == 2) {
                    try {
                        rootServer = InetAddress.getByName(commandArgs[1]);
                        System.out.println("Root DNS server is now: " + rootServer.getHostAddress());
                    } catch (UnknownHostException e) {
                        System.out.println("Invalid root server (" + e.getMessage() + ").");
                        continue;
                    }
                } else {
                    System.out.println("Invalid call. Format:\n\tserver IP");
                    continue;
                }
            } else if (commandArgs[0].equalsIgnoreCase("trace")) {
                // TRACE: Turn trace setting on or off
                if (commandArgs.length == 2) {
                    if (commandArgs[1].equalsIgnoreCase("on"))
                        verboseTracing = true;
                    else if (commandArgs[1].equalsIgnoreCase("off"))
                        verboseTracing = false;
                    else {
                        System.err.println("Invalid call. Format:\n\ttrace on|off");
                        continue;
                    }
                    System.out.println("Verbose tracing is now: " + (verboseTracing ? "ON" : "OFF"));
                } else {
                    System.err.println("Invalid call. Format:\n\ttrace on|off");
                    continue;
                }
            } else if (commandArgs[0].equalsIgnoreCase("lookup") ||
                    commandArgs[0].equalsIgnoreCase("l")) {
                // LOOKUP: Find and print all results associated to a name.
                RecordType type;
                if (commandArgs.length == 2)
                    type = RecordType.A;
                else if (commandArgs.length == 3)
                    try {
                        type = RecordType.valueOf(commandArgs[2].toUpperCase());
                    } catch (IllegalArgumentException ex) {
                        System.err.println("Invalid query type. Must be one of:\n\tA, AAAA, NS, MX, CNAME");
                        continue;
                    }
                else {
                    System.err.println("Invalid call. Format:\n\tlookup hostName [type]");
                    continue;
                }
                findAndPrintResults(commandArgs[1], type);
            } else if (commandArgs[0].equalsIgnoreCase("dump")) {
                // DUMP: Print all results still cached
                cache.forEachNode(DNSLookupService::printResults);
            } else {
                System.err.println("Invalid command. Valid commands are:");
                System.err.println("\tlookup fqdn [type]");
                System.err.println("\ttrace on|off");
                System.err.println("\tserver IP");
                System.err.println("\tdump");
                System.err.println("\tquit");
                continue;
            }

        } while (true);

        socket.close();
        System.out.println("Goodbye!");
    }

    /**
     * Finds all results for a host name and type and prints them on the standard output.
     *
     * @param hostName Fully qualified domain name of the host being searched.
     * @param type     Record type for search.
     */
    private static void findAndPrintResults(String hostName, RecordType type) {

        DNSNode node = new DNSNode(hostName, type);
        printResults(node, getResults(node, 0));
    }

    /**
     * Finds all the result for a specific node.
     *
     * @param node             Host and record type to be used for search.
     * @param indirectionLevel Control to limit the number of recursive calls due to CNAME redirection.
     *                         The initial call should be made with 0 (zero), while recursive calls for
     *                         regarding CNAME results should increment this value by 1. Once this value
     *                         reaches MAX_INDIRECTION_LEVEL, the function prints an error message and
     *                         returns an empty set.
     * @return A set of resource records corresponding to the specific query requested.
     */
    private static Set<ResourceRecord> getResults(DNSNode node, int indirectionLevel) {

        if (indirectionLevel > MAX_INDIRECTION_LEVEL) {
            System.err.println("Maximum number of indirection levels reached.");
            return Collections.emptySet();
        }

        // TODO To be completed by the student

        // if the DNSNode is a CNAME, have to do recursion to handle the root domain
        
        // if record is a CNAME, repeat search with a new node with the canonical name

        // look for record in the cache; if found, return
        if (cache.getCachedResults(node).isEmpty()) {

            // if not in cache, retrieve result from root server

            // check in cache for the result (retrieveResultsFromServer doesn't return, only caches)
            retrieveResultsFromServer(node, rootServer);

        }

        // return the 
        return cache.getCachedResults(node);
    }

    /**
     * Retrieves DNS results from a specified DNS server. Queries are sent in iterative mode,
     * and the query is repeated with a new server if the provided one is non-authoritative.
     * Results are stored in the cache.
     *
     * @param node   Host name and record type to be used for the query.
     * @param server Address of the server to be used for the query.
     */
    private static void retrieveResultsFromServer(DNSNode node, InetAddress server) {

        // TODO To be completed by the student
        String DNSServerAddress = new String(server.getHostAddress());

        // encode a query with a unique ID and host name & records
        int queryID = generateQueryID();

        if (verboseTracing) {

            /* For each query that is sent start by printing 2 blank lines. If a query is 
            repeated because of a timeout the query is to be reprinted at the time of the timeout. 
            Note that a resent query will have the same query ID.*/
            System.out.print("\n\n");

            /* Print the phrase "Query ID" followed by 5 spaces, then the query ID itself, a space, 
            the name being looked up, two spaces, then the query type (e.g., A or AAAA), a space, 
            "-->", another space and finally the IP address of the DNS server being consulted. */
            System.out.printf("Query ID     %d %s  %s --> %s\n", queryID, node.getHostName(), node.getType(), DNSServerAddress);

        }

        // create new DNSMessage object and set fields appropriately
        DNSMessage dnsMessage = new DNSMessage();
        dnsMessage.setQueryId(queryID);
        dnsMessage.setqName(node.getHostName());
        dnsMessage.setqType(node.getType().getCode());

        // encode query as a byte array to be sent through UDP socket
        byte[] encodedBytes = encodeDNSQuery(dnsMessage);

        // send as a query datagram through socket to the server
        DatagramPacket packet = new DatagramPacket(encodedBytes, encodedBytes.length, rootServer, DEFAULT_DNS_PORT);

        try {
            socket.send(packet);
            socket.setSoTimeout(5000);
            byte[] receiver = new byte[1024];
            DatagramPacket received = new DatagramPacket(receiver, receiver.length);
            try {
                socket.receive(received);
            } catch (SocketTimeoutException e) {
                // resend the packet
                if (verboseTracing) {
                    System.out.print("\n\n");
                    System.out.printf("Query ID     %d %s  %s --> %s\n", queryID, node.getHostName(), node.getType(), DNSServerAddress);
                }
                socket.send(packet);
            }
            // check received data...
            // TODO
            System.out.print(received);
            
        } catch(IOException ex) {
            // TODO do something
            ex.printStackTrace();
        } 

        // TODO: if no response received, resend the packet

        // decode the received packet



        // receive response datagram
        int responseID = 0;
        boolean isAuthoritative = true;
        int numResponses = 0;

        // decode response datagram
        if (verboseTracing) {

            /* The next line is the the phrase "Response ID:", a space, the ID of the response, a space, 
            the word Authoritative, a space, an equal sign, another space, and the word true or false to 
            indicate if this response is authoritative or not. */
            System.out.printf("Response ID: %d Authoritative = %b\n", responseID, isAuthoritative);

            /* The next line consists of 2 spaces, the word Answers, followed by a space and the number 
            of response records in the answer in parenthesis. */
            System.out.printf("  Answers (%d)\n", numResponses);

            /* For each response record you are to print the records Name, followed by its time-to-live, 
            record type in (one of A, AAAA, CN, NS, or else the type number) followed by the type value. 
            The formatting of the value depends upon its type. A format string that you can use with the 
            format method to achieve the formatting required when printing a resource record is found in 
            the method verbosePrintResourceRecord, provided with the code. */

        }
        // store response in the cache
    }

    /**
     * Encodes a DNSMessage object into a byte array representing the data to be wrapped in a datagram packet.
     * @param dnsMessage the DNSMessage object to translate
     * @return byte array representing a dns query
     */
    private static byte[] encodeDNSQuery(DNSMessage dnsMessage) {
        ByteArrayOutputStream bOutput = new ByteArrayOutputStream();
        int queryId = dnsMessage.getQueryId();
        try {
            // header section
            // convert query id from int to byte array
            byte[] intArray = ByteBuffer.allocate(4).putInt(queryId).array();
            byte[] queryIdArray = new byte[2];
            queryIdArray[0] = intArray[2];
            queryIdArray[1] = intArray[3];
            bOutput.write(queryIdArray);
            // qr, qpcode, aa, tc, rd, ra, z, rcode
            bOutput.write(0);
            bOutput.write(0);
            // qd count
            byte[] qdCount = new byte[2];
            qdCount[1] = (byte) 1;
            bOutput.write(qdCount);
            // ancount, nscount, arcount
            bOutput.write(0);
            bOutput.write(0);

            bOutput.write(0);
            bOutput.write(0);

            bOutput.write(0);
            bOutput.write(0);
            // question section
            // qname
            bOutput.write(domainToQname(dnsMessage.getqName()));
            // qtype
            bOutput.write(0);
            bOutput.write(dnsMessage.getqType());
            // qclass (set to 1 for IN)
            bOutput.write(0);
            bOutput.write(1);
        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
        // 4.2.1: UDP packets are 512 bytes maximum
        // TODO
        // THIS CODE BELOW IS JUST TO PRINT OUT THE ENCODED DNS QUERY - for testing only
//        byte[] example = bOutput.toByteArray();
//        int two = 1;
//        for (byte b : example) {
//            System.out.print(String.format("%02X ", b) + " ");
//            if (two == 2) {
//                two = 0;
//                System.out.println();
//            }
//            two++;
//        }
        return bOutput.toByteArray();
    }

    /**
     * Decodes a DNS response from a given byte array. Returns a query message.
     */
    private static DNSMessage decodeDNSQuery(byte[] response) {
        // assume response is less than 1024 bytes
        // TODO
        return null;
    }

    /**
     * Converts the domain name to a suitable format for Qname
     * @param hostName host name to translate
     * @return host name in the format of a QNAME (byte array)
     */
    private static byte[] domainToQname(String hostName) {
        String[] splitDomain = hostName.split("\\.");
        ByteArrayOutputStream dnameOutput = new ByteArrayOutputStream(splitDomain.length);
        for (String part : splitDomain) {
            // write the length of the label first
            dnameOutput.write(part.length());
            for (int index = 0; index < part.length(); index++) {
                // convert string to hex
                int ascii = (int) part.charAt(index);
                dnameOutput.write(ascii);
            }
        }
        dnameOutput.write(0);
        return dnameOutput.toByteArray();
    }

    /**
     * Generates a unique query ID between 0 and 65535.
     */
    private static int generateQueryID() {
        int n = random.nextInt(MAX_QUERY_ID + 1);

        if (generatedIDs[n] == 1) { // if the ID has already been generated, try again
            return generateQueryID();
        } else {
            generatedIDs[n] = 1; // set to used ID
            return n;
        }
    }

    /**
     * Generate a unique query ID between 0bx0000 and 0bxFFFF
     * @return
     */
    private static byte[] generateQueryIDBytes() {
        byte[] b = new byte[2];
        new Random().nextBytes(b);
        return b;
    }

    private static void verbosePrintResourceRecord(ResourceRecord record, int rtype) {
        if (verboseTracing)
            System.out.format("       %-30s %-10d %-4s %s\n", record.getHostName(),
                    record.getTTL(),
                    record.getType() == RecordType.OTHER ? rtype : record.getType(),
                    record.getTextResult());
    }

    /**
     * Prints the result of a DNS query.
     *
     * @param node    Host name and record type used for the query.
     * @param results Set of results to be printed for the node.
     */
    private static void printResults(DNSNode node, Set<ResourceRecord> results) {
        if (results.isEmpty())
            System.out.printf("%-30s %-5s %-8d %s\n", node.getHostName(),
                    node.getType(), -1, "0.0.0.0");
        for (ResourceRecord record : results) {
            System.out.printf("%-30s %-5s %-8d %s\n", node.getHostName(),
                    node.getType(), record.getTTL(), record.getTextResult());
        }
    }
}
