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
    private static InetAddress currentServer;
    private static InetAddress nextAddress; // next address to search if current search fails
    private static String nextNSRecord;
    private static boolean verboseTracing = false;
    private static DatagramSocket socket;
    private static String currentDomain;

    private static DNSCache cache = DNSCache.getInstance();

    private static Random random = new Random();
    final static int MAX_QUERY_ID = 0xFFFF; // must be 16 bit -> 0x0 to 0xFFFF

    private static int bytePosParse = 0; // keep track of position parsed so far

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
            currentServer = rootServer;
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
                        currentServer = rootServer;
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
                currentDomain = commandArgs[1];
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
        currentServer = rootServer;
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
        // return empty set if max indirection level is reached
        if (indirectionLevel > MAX_INDIRECTION_LEVEL) {
            System.err.println("Maximum number of indirection levels reached.");
            return Collections.emptySet();
        }

        DNSNode cnameNode = new DNSNode(node.getHostName(), RecordType.CNAME);
        Set<ResourceRecord> cnameRecords = cache.getCachedResults(cnameNode);
        if (!cnameRecords.isEmpty()) {
            // if a CNAME record exists for the node
            for (ResourceRecord cnameRecord : cnameRecords) {
                String newCnameQuery = cnameRecord.getTextResult();
                DNSNode newQuery = new DNSNode(newCnameQuery, node.getType());
                while (true) {
                    if (indirectionLevel > MAX_INDIRECTION_LEVEL) {
                        System.err.println("Maximum number of indirection levels reached.");
                        return Collections.emptySet();
                    }
                    // to check if there's CNAME RRs that are already in the cache
                    Set<ResourceRecord> cachedCnameResults = cache.getCachedResults(new DNSNode(newCnameQuery, RecordType.CNAME));
                    if (cachedCnameResults.isEmpty()) {
                        cnameNode = new DNSNode(newQuery.getHostName(), node.getType());
                        break;
                    } else {
                        for (ResourceRecord result : cachedCnameResults) {
                            // follow the CNAME into the cache
                            newCnameQuery = result.getTextResult();
                            newQuery = new DNSNode(newCnameQuery, node.getType());
                            indirectionLevel++;
                        }
                    }
                }
            }
        }

        node = new DNSNode(cnameNode.getHostName(), node.getType());

        // look for record in cache; if found return
        if (cache.getCachedResults(node).isEmpty()) {
            retrieveResultsFromServer(node, currentServer);
        }

        // if it's looking for a CNAME
        if (!cache.getCachedResults(node).isEmpty()) {
            return cache.getCachedResults(node);
        }

        // if the DNSNode is a CNAME, have to do recursion to handle the root domain
        cnameNode = new DNSNode(node.getHostName(), RecordType.CNAME);
        cnameRecords = cache.getCachedResults(cnameNode);
        if (node.getType() != RecordType.CNAME && !cnameRecords.isEmpty()) {
            // if the node we're looking for is not in the cache and is not a CNAME, but a CNAME exists in the cache
            // do a query again with the CNAME -> look for the IP address of the CNAME
            for (ResourceRecord cnameRecord : cnameRecords) {
                String newCnameQuery = cnameRecord.getTextResult();
                DNSNode newQuery = new DNSNode(newCnameQuery, node.getType());

                while (true) {
                    if (indirectionLevel > MAX_INDIRECTION_LEVEL) {
                        System.err.println("Maximum number of indirection levels reached.");
                        return Collections.emptySet();
                    }
                    // to check if there's CNAME RRs that are already in the cache
                    Set<ResourceRecord> cachedCnameResults = cache.getCachedResults(new DNSNode(newCnameQuery, RecordType.CNAME));
                    if (cachedCnameResults.isEmpty()) {
                        break;
                    } else {
                        for (ResourceRecord result : cachedCnameResults) {
                            // follow the CNAME into the cache
                            newCnameQuery = result.getTextResult();
                            newQuery = new DNSNode(newCnameQuery, node.getType());
                            indirectionLevel++;
                        }
                    }
                }
                return getResults(newQuery, indirectionLevel + 1);
            }
            // ----- end
        }

        // if there's a next address, query with new address
        if (nextAddress != null) {
            currentServer = nextAddress;
            nextAddress = null;
            return getResults(node, indirectionLevel); // redo search with a new address
        } else if (nextNSRecord != null) {
            DNSNode resolveNSNode = new DNSNode(nextNSRecord, RecordType.A);
            nextNSRecord = null;
            Set<ResourceRecord> nsRecords = getResults(resolveNSNode, 0);
            for (ResourceRecord nsRecord : nsRecords) {
                currentServer = nsRecord.getInetResult();
                return getResults(node, 0);
            }
        }

        // check in cache for the result and return (retrieveResultsFromServer doesn't return, only caches)
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

        String DNSServerAddress = server.getHostAddress();

        // encode a query with a unique ID and host name & records
        int queryID = generateQueryID();

        // create new DNSMessage object and set fields appropriately
        DNSMessage dnsMessage = new DNSMessage();
        DNSQuestionEntry question = new DNSQuestionEntry(node.getHostName(), node.getType().getCode(), 1);
        dnsMessage.addQuestion(question);
        dnsMessage.setQueryId(queryID);

        // encode query as a byte array to be sent through UDP socket
        byte[] encodedBytes = encodeDNSQuery(dnsMessage);

        // send as a query datagram through socket to the server
        DatagramPacket packet = new DatagramPacket(encodedBytes, encodedBytes.length, server, DEFAULT_DNS_PORT);
        String queryPrintString = String.format("Query ID     %d %s  %s --> %s\n", queryID, node.getHostName(), node.getType(), DNSServerAddress);

        DatagramPacket received = sendPacket(packet, queryPrintString);

        if (received == null) // no packet received, break and print -1
            return;

        // decode the received packet
        DNSMessage response = decodeDNSQuery(received.getData());
        if (response == null)
            return;

        // receive response datagram
        boolean isAuthoritative = (response.getAA() == 1);

        // decode response datagram
        if (verboseTracing) {
            System.out.printf("Response ID: %d Authoritative = %b\n", response.getQueryId(), isAuthoritative);

            /* The next line consists of 2 spaces, the word Answers, followed by a space and the number 
            of response records in the answer in parenthesis. */
            System.out.printf("  Answers (%d)\n", response.getAnCount());
            for (ResourceRecord record : response.getAnswerRRs()) {
                verbosePrintResourceRecord(record, record.getType().getCode());
            }
            System.out.printf("  Nameservers (%d)\n", response.getNsCount());
            for (ResourceRecord record : response.getAuthorityRRs()) {
                verbosePrintResourceRecord(record, record.getType().getCode());
            }
            System.out.printf("  Additional Information (%d)\n", response.getArCount());
            for (ResourceRecord record : response.getAdditionalRRs()) {
                verbosePrintResourceRecord(record, record.getType().getCode());
            }

        }
        // store response in the cache
        bytePosParse = 0;

        if (!isAuthoritative) {
            // if the response isn't authoritative
            List<ResourceRecord> additional = filterARecords(response.getAdditionalRRs());
            if (additional.size() >= 1) {
                for (ResourceRecord resourceRecord : additional) {
                    if (resourceRecord.getHostName().equals(currentDomain)) {
                        nextAddress = resourceRecord.getInetResult();
                        return;
                    }
                }
                // set next DNS server to query as the first additional record
                nextAddress = additional.get(0).getInetResult();
            } else {
                // if the response is authoritative
                List<ResourceRecord> authorities = filterNSRecords(response.getAuthorityRRs());
                if (authorities.size() >= 1) {
                    //if there's name sever records, reset the current server to query to original root server
                    currentServer = rootServer;
                    for (ResourceRecord authority : authorities) {
                        if (authority.getHostName().equals(currentDomain)) {
                            nextNSRecord = authority.getTextResult();
                            return;
                        }
                    }
                    nextNSRecord = authorities.get(0).getTextResult();
                }
            }
        } else {
            // if the server is authoritative, reset the current server to query to original root server
            currentServer = rootServer;
        }
    }

    /**
     * Returns a list of only AR resource records from a list of RRs
     *
     * @param records list of records to filter
     * @return a list of only name server records from the input list
     */
    private static List<ResourceRecord> filterARecords(List<ResourceRecord> records) {
        List<ResourceRecord> aRecords = new ArrayList<>();
        for (ResourceRecord record : records) {
            if (record.getType() == RecordType.A) {
                aRecords.add(record);
            }
        }
        return aRecords;
    }

    /**
     * Returns a list of only NS resource records from a list of RRs
     *
     * @param records list of records to filter
     * @return a list of only name server records from the input list
     */
    private static List<ResourceRecord> filterNSRecords(List<ResourceRecord> records) {
        List<ResourceRecord> nameServerRecords = new ArrayList<>();
        for (ResourceRecord record : records) {
            if (record.getType() == RecordType.NS) {
                nameServerRecords.add(record);
            }
        }
        return nameServerRecords;
    }

    /**
     * Send DNS query.
     */
    private static DatagramPacket sendPacket(DatagramPacket packet, String queryPrintString) {
        byte[] receiver = new byte[1024];
        DatagramPacket received = new DatagramPacket(receiver, receiver.length);
        try {
            if (verboseTracing) {
                System.out.print("\n\n");
                System.out.printf(queryPrintString);
            }
            socket.send(packet);
            try {
                socket.receive(received);
            } catch (SocketTimeoutException e) {
                // resend the packet
                if (verboseTracing) {
                    System.out.print("\n\n");
                    System.out.printf(queryPrintString);
                }
                // packet.setAddress(rootServer); // for testing timeout (2nd time work)
                socket.send(packet);
                try {
                    socket.receive(received);
                } catch (Exception ex) {
                    // second attempt failed, just return -1
                    return null;
                }
            }

        } catch (IOException ex) {
            return null;
        }
        return received;
    }

    /**
     * Encodes a DNSMessage object into a byte array representing the data to be wrapped in a datagram packet.
     *
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
            bOutput.write(1);
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
            for (int i = 0; i < dnsMessage.getQuestions().size(); i++) {
                // qname
                bOutput.write(domainToQname(dnsMessage.getQuestions().get(i).getQname()));
                // qtype
                bOutput.write(0);
                bOutput.write(dnsMessage.getQuestions().get(i).getQtype());
                // qclass (set to 1 for IN)
                bOutput.write(0);
                bOutput.write(1);
            }

        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
        // THIS CODE BELOW IS JUST TO PRINT OUT THE ENCODED DNS QUERY - for testing only
        // decodeDNSQuery(bOutput.toByteArray());
        return bOutput.toByteArray();
    }

    /**
     * Decodes a DNS response from a given byte array. Returns a query message.
     */
    private static DNSMessage decodeDNSQuery(byte[] response) {
        // assume response is less than 1024 bytes
        DNSMessage message = new DNSMessage();

        // ------ HEADER ------

        // ID (16 bits - 2 bytes)
        byte[] parsedId = {response[0], response[1]};
        int queryId = bytesToInt(parsedId);
        message.setQueryId(queryId);

        // QR (1 bit)
        int QR = getBitAtPosition(response[2], 0);
        message.setQr(QR);

        // OPCODE (4 bits)
        int[] parsedOpcode = new int[4];
        for (int i = 1; i <= 4; i++) {
            parsedOpcode[i - 1] = getBitAtPosition(response[2], i);
        }
        int OPCODE = bitsToInt(parsedOpcode);
        message.setOpCode(OPCODE);

        // AA (1 bit)
        int AA = getBitAtPosition(response[2], 5);
        message.setAA(AA);

        // TC (1 bit)
        int TC = getBitAtPosition(response[2], 6);
        message.setTC(TC);

        // RD (1 bit)
        int RD = getBitAtPosition(response[2], 7);
        message.setRD(RD);

        // RA (1 bit)
        int RA = getBitAtPosition(response[3], 0);
        message.setRA(RA);

        // Z (3 bits)
        int[] parsedZ = new int[4];
        for (int i = 1; i <= 3; i++) {
            parsedZ[i - 1] = getBitAtPosition(response[3], i);
        }
        int Z = bitsToInt(parsedZ);
        message.setZ(Z);

        // RCODE (4 bits)
        int[] parsedRcode = new int[4];
        for (int i = 4; i <= 7; i++) {
            parsedRcode[i - 4] = getBitAtPosition(response[3], i);
        }
        int RCODE = bitsToInt(parsedRcode);
        if (RCODE == 3) {
            return null;
        }
        message.setRCODE(RCODE);

        // QDCOUNT (16 bits)
        int[] parsedQDCount = new int[16];
        for (int i = 0; i <= 7; i++) {
            parsedQDCount[i] = getBitAtPosition(response[4], i);
        }
        for (int i = 0; i <= 7; i++) {
            parsedQDCount[8 + i] = getBitAtPosition(response[5], i);
        }
        int QDCOUNT = bitsToInt(parsedQDCount);
        message.setQdCount(QDCOUNT);

        // ANCOUNT (16 bits)
        int[] parsedAncount = new int[16];
        for (int i = 0; i <= 7; i++) {
            parsedAncount[i] = getBitAtPosition(response[6], i);
        }
        for (int i = 0; i <= 7; i++) {
            parsedAncount[8 + i] = getBitAtPosition(response[7], i);
        }
        int ANCOUNT = bitsToInt(parsedAncount);
        message.setAnCount(ANCOUNT);

        // NSCOUNT (16 bits)
        int[] parsedNscount = new int[16];
        for (int i = 0; i <= 7; i++) {
            parsedNscount[i] = getBitAtPosition(response[8], i);
        }
        for (int i = 0; i <= 7; i++) {
            parsedNscount[8 + i] = getBitAtPosition(response[9], i);
        }
        int NSCOUNT = bitsToInt(parsedNscount);
        message.setNsCount(NSCOUNT);

        // ARCOUNT (16 bits)
        int[] parsedArcount = new int[16];
        for (int i = 0; i <= 7; i++) {
            parsedArcount[i] = getBitAtPosition(response[10], i);
        }
        for (int i = 0; i <= 7; i++) {
            parsedArcount[8 + i] = getBitAtPosition(response[11], i);
        }
        int ARCOUNT = bitsToInt(parsedArcount);
        message.setArCount(ARCOUNT);

        bytePosParse = 12; // byte to start parsing variable length entries

        // ------ QUESTION ------
        // variable length

        for (int qNum = 0; qNum < QDCOUNT; qNum++) {

            // QNAME - variable length
            String QNAME = getDomainAt(response, bytePosParse, true);

            // QTYPE (2 octets = 2 bytes = 16 bits)
            byte[] parsedQtype = {response[bytePosParse], response[bytePosParse + 1]};
            bytePosParse += 2;
            int QTYPE = bytesToInt(parsedQtype);

            // QCLASS (2 octets = 2 bytes = 16 bits)
            byte[] parsedQclass = {response[bytePosParse], response[bytePosParse + 1]};
            bytePosParse += 2;
            int QCLASS = bytesToInt(parsedQclass);

            DNSQuestionEntry question = new DNSQuestionEntry(QNAME, QTYPE, QCLASS);
            message.addQuestion(question);
        }

        // ------ ANSWER ------

        for (int ansNum = 0; ansNum < ANCOUNT; ansNum++) {
            ResourceRecord record = parseResourceRecord(response);
            message.addAnswerRR(record);
            cache.addResult(record);
        }

        // ------ AUTHORITY ------

        for (int ansNum = 0; ansNum < NSCOUNT; ansNum++) {
            ResourceRecord record = parseResourceRecord(response);
            message.addAuthorityRR(record);
            cache.addResult(record);
        }

        // ------ ADDITIONAL ------

        for (int ansNum = 0; ansNum < ARCOUNT; ansNum++) {
            ResourceRecord record = parseResourceRecord(response);
            message.addAdditionalRR(record);
            cache.addResult(record);
        }

        return message;
    }

    private static ResourceRecord parseResourceRecord(byte[] response) {

        // NAME - variable length
        String NAME = getDomainAt(response, bytePosParse, true);

        // TYPE
        byte[] typeBytes = {response[bytePosParse], response[bytePosParse + 1]};
        bytePosParse += 2;
        RecordType TYPE;
        TYPE = RecordType.getByCode(bytesToInt(typeBytes));

        // CLASS
        byte[] classBytes = {response[bytePosParse], response[bytePosParse + 1]};
        bytePosParse += 2;
        int CLASS = bytesToInt(classBytes);

        // TTL (32-bit; 4 bytes)
        byte[] ttlBytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            ttlBytes[i] = response[bytePosParse];
            bytePosParse++;
        }
        int TTL = bytesToInt(ttlBytes);

        // RDLENGTH (16-bit, 2 bytes)
        byte[] rdlengthBytes = {response[bytePosParse], response[bytePosParse + 1]};
        bytePosParse += 2;
        int RDLENGTH = bytesToInt(rdlengthBytes);

        // RDATA
        byte[] rdataBytes = new byte[RDLENGTH];
        for (int i = 0; i < RDLENGTH; i++) {
            rdataBytes[i] = response[bytePosParse];
            bytePosParse++;
        }
        String RDATA = "";
        try {
            if (TYPE == RecordType.A || TYPE == RecordType.AAAA) {
                RDATA = convertAddressRecordData(rdataBytes, TYPE);
                return new ResourceRecord(NAME, TYPE, (long) TTL, InetAddress.getByName(RDATA));
            } else if (TYPE == RecordType.CNAME || TYPE == RecordType.NS) {
                RDATA = getDomainAt(response, bytePosParse - RDLENGTH, false);
            } else {
                RDATA = "----";
            }
        } catch (UnknownHostException ex) {
            System.out.println(ex);
        }

        return new ResourceRecord(NAME, TYPE, (long) TTL, RDATA);
    }


    /**
     * Return an IP Address as a string depending on the RR type
     *
     * @param data array of bytes to convert
     * @param type resource record type
     * @return correct string representation (i.e. IPv4 Address for type A in dotted decimal notation)
     */
    private static String convertAddressRecordData(byte[] data, RecordType type) {
        if (type == RecordType.A) {
            // record type is A
            return getIpv4Address(data);
        } else {
            // record type is AAAA
            return getIpv6Address(data);
        }
    }

    /**
     * Converts an array of bytes into an IPv4 address
     *
     * @param data array of bytes to convert
     * @return IPv4 address as a String
     */
    private static String getIpv4Address(byte[] data) {
        StringBuilder ipAddress = new StringBuilder();
        for (byte b : data) {
            // convert each byte to an unsigned int
            ipAddress.append((int) b & 0xFF).append(".");
        }
        int addressLength = ipAddress.toString().length();
        // remove the last extra period
        return ipAddress.toString().substring(0, addressLength - 1);
    }

    /**
     * Converts an array of bytes into an IPv6 address
     *
     * @param data array of bytes to convert
     * @return IPv6 address as a String
     */
    private static String getIpv6Address(byte[] data) {
        StringBuilder ipAddress = new StringBuilder();
        boolean colonFlag = false;
        for (byte datum : data) {
            ipAddress.append(String.format("%02x", datum));
            if (colonFlag) {
                // append a colon for every second byte
                ipAddress.append(":");
                colonFlag = false;
            } else {
                colonFlag = true;
            }
        }
        // split the address by colons
        String[] splitAddress = ipAddress.toString().split(":");
        StringBuilder ipAddressBuilder = new StringBuilder();
        for (String s : splitAddress) {
            // convert to int to remove trailing and leading zeroes
            ipAddressBuilder.append(String.format("%x", Integer.parseInt(s, 16))).append(":");
        }
        // removes the last extra colon from the address
        return ipAddressBuilder.toString().substring(0, ipAddressBuilder.toString().length() - 1);
    }

    private static String getDomainAt(byte[] response, int position, boolean incPos) {
        // for resolving message compression pointers

        // NAME length (8 bits - 1 byte)
        String NAME = "";

        while (true) {

            int currNameLength = response[position] & 0xFF;
            if (currNameLength == 0) {
                if (incPos)
                    bytePosParse++;
                break;
            }

            if (getBitAtPosition(response[position], 0) == 1 && getBitAtPosition(response[position], 1) == 1) {
                // domain name is a pointer
                int[] tempOffset = new int[14]; // bits to convert to offset
                for (int i = 2; i <= 7; i++) {
                    tempOffset[i - 2] = getBitAtPosition(response[position], i);
                }
                position++;
                for (int i = 0; i <= 7; i++) {
                    tempOffset[i + 6] = getBitAtPosition(response[position], i);
                }
                position++;
                int offset = bitsToInt(tempOffset);

                if (incPos)
                    bytePosParse += 2;

                NAME += getDomainAt(response, offset, false);
                break;

            } else {
                position++;
                if (incPos)
                    bytePosParse++;

                byte[] qNameDomainBytes = new byte[currNameLength];
                // QNAME domains
                for (int currQnameOctet = 0; currQnameOctet < currNameLength; currQnameOctet++) {
                    qNameDomainBytes[currQnameOctet] += response[position];
                    position++;
                    if (incPos)
                        bytePosParse++;
                }
                try {
                    String asciiDomain = new String(qNameDomainBytes, "UTF-8");
                    NAME += asciiDomain + ".";
                } catch (Exception e) {
                    continue;
                }
            }

        }

        try {
            if (NAME.substring(NAME.length() - 1).equals(".")) {
                NAME = NAME.substring(0, NAME.length() - 1);
            }
        } catch (Exception e) {

        }
        return NAME; // end QNAME if null byte (00)
    }

    /**
     * Utility function to print a datagram packet in bits, as formatted in RFCs. For debugging purposes.
     */
    private static void printDatagramPacketInBits(byte[] response) {
        int linecount = 1; // num bytes printed, should be 2
        for (int i = 0; i < response.length; i++) {
            for (int j = 0; j < 8; j++) {
                System.out.print(getBitAtPosition(response[i], j));
            }
            linecount++;
            if (linecount > 2) {
                System.out.print("\n");
                linecount = 1;
            }
        }

    }

    /**
     * Utiity function to convert bits to an integer.
     */
    private static int bitsToInt(int[] bits) {
        int value = 0;
        for (int i = 0; i < bits.length; i++) {
            value += bits[i] * (int) Math.pow(2, bits.length - 1 - i);
        }
        return value;
    }

    /**
     * Utility function to get a bit at a given position in a byte.
     *
     * @param inputByte
     * @param position  Position from the left (as if the bits are an array)
     * @return int (either 1 or 0) which is the bit at the given position
     */
    private static int getBitAtPosition(byte inputByte, int position) {
        //System.out.print(inputByte);
        int pos = 7 - position; // this is because a bitwise shift
        // starts from the right side
        // System.out.print((inputByte >> pos) & 1);
        return ((inputByte >> pos) & 1);
    }

    /**
     * Utility function to convert number of bytes to an int. Only supports 2 or 4 bytes.
     *
     * @param bytes byte array to convert
     * @return int conversion of the byte array
     */
    private static int bytesToInt(byte[] bytes) {
        if (bytes.length == 2) {
            return (bytes[0] & 0xff) << 8 | (bytes[1] & 0xff);
        } else if (bytes.length == 4) {
            return bytes[0] << 24 | (bytes[1] & 0xff) << 16 | (bytes[2] & 0xff) << 8
                    | (bytes[3] & 0xff);
        } else {
            System.out.print("conversion of bytes to int not supported");
            return 0;
        }
    }

    /**
     * Utility function for printing a byte array. For debugging purposes.
     */
    private static void printByteArray(byte[] byteArray) {
        for (byte b : byteArray) {
            System.out.print(String.format("%02X", b) + " ");
        }
        System.out.println();
    }

    /**
     * Converts the domain name to a suitable format for Qname
     *
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
