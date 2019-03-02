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

        // if it's looking for a CNAME
        if (!cache.getCachedResults(node).isEmpty() && node.getType() == RecordType.CNAME) {
            return cache.getCachedResults(node);
        }

        // look for record in the cache; if found, return
        if (cache.getCachedResults(node).isEmpty()) {
            // if not in cache, retrieve result from root server
            retrieveResultsFromServer(node, rootServer);

        }

        // if the DNSNode is a CNAME, have to do recursion to handle the root domain
        if (node.getType() != RecordType.CNAME) {

        }

        // if record is a CNAME, repeat search with a new node with the canonical name

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

        // TODO To be completed by the student
        String DNSServerAddress = new String(server.getHostAddress());

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

        // receive response datagram
        boolean isAuthoritative = (response.getAA() == 1);
        int numResponses = 0;

        // decode response datagram
        if (verboseTracing) {

            System.out.printf("Response ID: %d Authoritative = %b\n", response.getQueryId(), isAuthoritative);

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
     * Send DNS query.
     */
    private static DatagramPacket sendPacket(DatagramPacket packet, String queryPrintString) {

        //For testing timeout:
        /* try {
            InetAddress server = InetAddress.getByName("www.google.ca");
            packet.setAddress(server);
        } catch (Exception e) {
            System.out.print(e);
        } */

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
            // TODO do something for IO?
            // ex.printStackTrace();
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
        // TODO

        // DEBUGGING:
        // printByteArray(response);
        // printDatagramPacketInBits(response);

        DNSMessage message = new DNSMessage();

        // ------ HEADER ------ 

        // ID (16 bits - 2 bytes)
        byte[] parsedId = {response[0], response[1]};
        int queryId = bytesToInt(parsedId);
        message.setQueryId(queryId);

        // QR (1 bit)
        int QR = getBitAtPosition(response[2], 0);
        System.out.println("QR: " + QR);
        message.setQr(QR);

        // OPCODE (4 bits)
        int[] parsedOpcode = new int[4];
        for (int i = 1; i <= 4; i++) {
            parsedOpcode[i - 1] = getBitAtPosition(response[2], i);
        }
        int OPCODE = bitsToInt(parsedOpcode);
        message.setOpCode(OPCODE);
        System.out.println("OPCODE: " + OPCODE);

        // AA (1 bit)
        int AA = getBitAtPosition(response[2], 5);
        message.setAA(AA);
        System.out.println("AA: " + AA);

        // TC (1 bit)
        int TC = getBitAtPosition(response[2], 6);
        message.setTC(TC);
        System.out.println("TC: " + TC);

        // RD (1 bit)
        int RD = getBitAtPosition(response[2], 7);
        message.setRD(RD);
        System.out.println("RD: " + RD);

        // RA (1 bit)
        int RA = getBitAtPosition(response[3], 0);
        message.setRA(RA);
        System.out.println("RA: " + RA);

        // Z (3 bits)
        int[] parsedZ = new int[4];
        for (int i = 1; i <= 3; i++) {
            parsedZ[i - 1] = getBitAtPosition(response[3], i);
        }
        int Z = bitsToInt(parsedZ);
        message.setZ(Z);
        System.out.println("Z: " + Z);

        // RCODE (4 bits)
        int[] parsedRcode = new int[4];
        for (int i = 1; i <= 3; i++) {
            parsedRcode[i - 1] = getBitAtPosition(response[3], i);
        }
        int RCODE = bitsToInt(parsedRcode);
        message.setRCODE(RCODE);
        System.out.println("RCODE: " + RCODE);

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
        System.out.println("QDCOUNT: " + QDCOUNT);

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
        System.out.println("ANCOUNT: " + ANCOUNT);

        // NSCOUNT (16 bits)
        int[] parsedNscount = new int[16];
        for (int i = 0; i <= 7; i++) {
            parsedNscount[i] = getBitAtPosition(response[8], i);
        }
        for (int i = 0; i <= 7; i++) {
            parsedNscount[8 + i] = getBitAtPosition(response[9], i);
        }
        int NSCOUNT = bitsToInt(parsedNscount);
        message.setAnCount(NSCOUNT);
        System.out.println("NSCOUNT: " + NSCOUNT);

        // ARCOUNT (16 bits)
        int[] parsedArcount = new int[16];
        for (int i = 0; i <= 7; i++) {
            parsedArcount[i] = getBitAtPosition(response[10], i);
        }
        for (int i = 0; i <= 7; i++) {
            parsedArcount[8 + i] = getBitAtPosition(response[11], i);
        }
        int ARCOUNT = bitsToInt(parsedArcount);
        message.setAnCount(ARCOUNT);
        System.out.println("ARCOUNT: " + ARCOUNT);

        bytePosParse = 12; // byte to start parsing variable length entries

        // ------ QUESTION ------ 
        // variable length

        for (int qNum = 0; qNum < QDCOUNT; qNum++) {

            // QNAME - variable length
            String QNAME = "";
            while (true) {
                // QNAME length (8 bits - 1 byte)
                int currQnameLength = response[bytePosParse];
                bytePosParse++;

                if (currQnameLength == 0) {
                    // remove last "." if end of domain
                    QNAME = QNAME.substring(0, QNAME.length() - 1);
                    break; // end QNAME if null byte (00)
                }

                byte[] qNameDomainBytes = new byte[currQnameLength];
                // QNAME domains
                for (int currQnameOctet = 0; currQnameOctet < currQnameLength; currQnameOctet++) {
                    qNameDomainBytes[currQnameOctet] += response[bytePosParse];
                    bytePosParse++;
                }
                try {
                    String asciiDomain = new String(qNameDomainBytes, "UTF-8");
                    QNAME += asciiDomain + ".";
                } catch (Exception e) {
                    continue;
                }
            }
            // TODO SET QNAME - need to refactor QNAME to support an array of questions
            System.out.println("QNAME: " + QNAME);

            // QTYPE (2 octets = 2 bytes = 16 bits)
            byte[] parsedQtype = {response[bytePosParse], response[bytePosParse]};
            bytePosParse += 2;
            int QTYPE = bytesToInt(parsedQtype);
            System.out.println("QTYPE: " + QTYPE);
            // TODO SET QTYPE 

            // QCLASS (2 octets = 2 bytes = 16 bits)
            byte[] parsedQclass = {response[bytePosParse], response[bytePosParse]};
            bytePosParse += 2;
            int QCLASS = bytesToInt(parsedQclass);
            System.out.println("QCLASS: " + QCLASS);
            // TODO SET QCLASS 

            DNSQuestionEntry question = new DNSQuestionEntry(QNAME, QTYPE, QCLASS);
            message.addQuestion(question);
        }

        // TODO!

        // ------ ANSWER ------

        for (int ansNum = 0; ansNum < ANCOUNT; ansNum++) {

            System.out.println("----- ANSWER #" + ansNum);

            // NAME - variable length
            String NAME = getDomainAt(response, bytePosParse, true);
            System.out.println("NAME: " + NAME);

            // TYPE 
            byte[] typeBytes = {response[bytePosParse], response[bytePosParse + 1]};
            bytePosParse += 2;
            RecordType TYPE;
            TYPE = RecordType.getByCode(bytesToInt(typeBytes));
            System.out.println("TYPE: " + TYPE.getCode());

            // CLASS
            byte[] classBytes = {response[bytePosParse], response[bytePosParse + 1]};
            bytePosParse += 2;
            int CLASS = bytesToInt(classBytes);
            System.out.println("CLASS: " + CLASS);

            // TTL (32-bit; 4 bytes)
            byte[] ttlBytes = new byte[4];
            for (int i = 0; i < 4; i++) {
                ttlBytes[i] = response[bytePosParse];
                bytePosParse++;
            }
            int TTL = bytesToInt(ttlBytes);
            System.out.println("TTL: " + TTL);

            // RDLENGTH (16-bit, 2 bytes)
            byte[] rdlengthBytes = {response[bytePosParse], response[bytePosParse + 1]};
            bytePosParse += 2;
            int RDLENGTH = bytesToInt(rdlengthBytes);
            System.out.println("RDLENGTH: " + RDLENGTH);

            // RDATA
            byte[] rdataBytes = new byte[RDLENGTH];
            for (int i = 0; i < RDLENGTH; i++) {
                rdataBytes[i] = response[bytePosParse];
                bytePosParse++;
            }
            String RDATA = "";
            try {
                RDATA = convertRecordData(rdataBytes, TYPE);
            } catch (UnsupportedEncodingException ex) {
                System.out.println(ex);
            }

            System.out.println("RDATA: " + RDATA);
            ResourceRecord record = new ResourceRecord(NAME, TYPE, (long) TTL, RDATA);
            message.addAnswerRR(record);
            cache.addResult(record);
        }

        // ------ AUTHORITY ------

        for (int ansNum = 0; ansNum < NSCOUNT; ansNum++) {

            System.out.println("----- AUTHORITY #" + ansNum);

            // NAME - variable length
            String NAME = getDomainAt(response, bytePosParse, true);
            System.out.println("NAME: " + NAME);

            // TYPE 
            byte[] typeBytes = {response[bytePosParse], response[bytePosParse + 1]};
            bytePosParse += 2;
            RecordType TYPE;
            TYPE = RecordType.getByCode(bytesToInt(typeBytes));
            System.out.println("TYPE: " + TYPE.getCode());

            // CLASS
            byte[] classBytes = {response[bytePosParse], response[bytePosParse + 1]};
            bytePosParse += 2;
            int CLASS = bytesToInt(classBytes);
            System.out.println("CLASS: " + CLASS);

            // TTL (32-bit; 4 bytes)
            byte[] ttlBytes = new byte[4];
            for (int i = 0; i < 4; i++) {
                ttlBytes[i] = response[bytePosParse];
                bytePosParse++;
            }
            int TTL = bytesToInt(ttlBytes);
            System.out.println("TTL: " + TTL);

            // RDLENGTH (16-bit, 2 bytes)
            byte[] rdlengthBytes = {response[bytePosParse], response[bytePosParse + 1]};
            bytePosParse += 2;
            int RDLENGTH = bytesToInt(rdlengthBytes);
            System.out.println("RDLENGTH: " + RDLENGTH);

            // RDATA
            byte[] rdataBytes = new byte[RDLENGTH];
            for (int i = 0; i < RDLENGTH; i++) {
                rdataBytes[i] = response[bytePosParse];
                bytePosParse++;
            }
            String RDATA = "";
            try {
                RDATA = convertRecordData(rdataBytes, TYPE);
            } catch (UnsupportedEncodingException ex) {
                System.out.println(ex);
            }

            System.out.println("RDATA: " + RDATA);
            ResourceRecord record = new ResourceRecord(NAME, TYPE, (long) TTL, RDATA);
            message.addAuthorityRR(record);
            cache.addResult(record);
        }

        // ------ ADDITIONAL ------

        for (int ansNum = 0; ansNum < ARCOUNT; ansNum++) {

            System.out.println("----- ADDITIONAL #" + ansNum);

            // NAME - variable length
            String NAME = getDomainAt(response, bytePosParse, true);
            System.out.println("NAME: " + NAME);

            // TYPE 
            byte[] typeBytes = {response[bytePosParse], response[bytePosParse + 1]};
            bytePosParse += 2;
            RecordType TYPE;
            TYPE = RecordType.getByCode(bytesToInt(typeBytes));
            System.out.println("TYPE: " + TYPE.getCode());

            // CLASS
            byte[] classBytes = {response[bytePosParse], response[bytePosParse + 1]};
            bytePosParse += 2;
            int CLASS = bytesToInt(classBytes);
            System.out.println("CLASS: " + CLASS);

            // TTL (32-bit; 4 bytes)
            byte[] ttlBytes = new byte[4];
            for (int i = 0; i < 4; i++) {
                ttlBytes[i] = response[bytePosParse];
                bytePosParse++;
            }
            int TTL = bytesToInt(ttlBytes);
            System.out.println("TTL: " + TTL);

            // RDLENGTH (16-bit, 2 bytes)
            byte[] rdlengthBytes = {response[bytePosParse], response[bytePosParse + 1]};
            bytePosParse += 2;
            int RDLENGTH = bytesToInt(rdlengthBytes);
            System.out.println("RDLENGTH: " + RDLENGTH);

            // RDATA
            byte[] rdataBytes = new byte[RDLENGTH];
            for (int i = 0; i < RDLENGTH; i++) {
                rdataBytes[i] = response[bytePosParse];
                bytePosParse++;
            }
            String RDATA = "";
            try {
                RDATA = convertRecordData(rdataBytes, TYPE);
            } catch (UnsupportedEncodingException ex) {
                System.out.println(ex);
            }

            System.out.println("RDATA: " + RDATA);
            ResourceRecord record = new ResourceRecord(NAME, TYPE, (long) TTL, RDATA);
            message.addAdditionalRR(record);
            cache.addResult(record);
        }
        return message;
    }


    /**
     * Return the correct string representation of RDATA depending on the RR type
     * @param data array of bytes to convert
     * @param type resource record type
     * @return correct string representation (i.e. IPv4 Address for type A in dotted decimal notation)
     * @throws UnsupportedEncodingException unsupported encoding exception
     */
    private static String convertRecordData(byte[] data, RecordType type) throws UnsupportedEncodingException {
        // TODO: need to do case for types CNAME and NS
        if (type == RecordType.A) {
            return getIpv4Address(data);
        } else if (type == RecordType.AAAA) {
            return getIpv6Address(data);
        } else {
            // TODO: remove this fail clause
            return new String(data, "UTF-8");
        }
    }
    /**
     * Converts an array of bytes into an IPv4 address
     * @param data array of bytes to convert
     * @return IPv4 address as a String
     */
    private static String getIpv4Address(byte[] data) {
        StringBuilder ipAddress = new StringBuilder();
        for (byte b : data) {
            // unsigned int
            ipAddress.append((int) b & 0xFF).append(".");
        }
        int addressLength = ipAddress.toString().length();
        return ipAddress.toString().substring(0, addressLength - 1);
    }

    /**
     * Converts an array of bytes into an IPv6 address
     * @param data array of bytes to convert
     * @return IPv6 address as a String
     */
    private static String getIpv6Address(byte[] data) {
        StringBuilder ipAddress = new StringBuilder();
        boolean colonFlag = false;
        for (byte datum : data) {
            ipAddress.append(String.format("%02X", datum));
            if (colonFlag) {
                ipAddress.append(":");
                colonFlag = false;
            } else {
                colonFlag = true;
            }
        }
        int addressLength = ipAddress.toString().length();
        return ipAddress.toString().substring(0, addressLength - 1);
    }

    private static String getDomainAt(byte[] response, int position, boolean incPos) {
        // for resolving message compression pointers

        // NAME length (8 bits - 1 byte)
        String NAME = "";

        while (true) {

            int currNameLength = response[position] & 0xFF;
            if (currNameLength == 0) {
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
                System.out.println("offset: " + offset);

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

        if (NAME.substring(NAME.length() - 1).equals(".")) {
            NAME = NAME.substring(0, NAME.length() - 1);
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

    /**
     * Generate a unique query ID between 0bx0000 and 0bxFFFF
     *
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
