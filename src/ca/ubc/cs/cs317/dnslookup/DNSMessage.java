package ca.ubc.cs.cs317.dnslookup;

/**
 * This class represents a DNS query/response.
 */

public class DNSMessage {
    private int RA; // recursion available (1 bit)
    private int Z; // reserved for future use - keep at 0 (3 bits)
    private int RCODE; // response code (4 bits)
    private byte[] queryId; // query id (16 bits)
    private int qClass; // a two octet code that specifies the class of the query (16 bits)
    private int qType; // a two octet code which specifies the type of the query (16 bits)
    private String qName; // represents the domain name
    private int qdCount; // query count
    private int anCount; // answer count
    private int nsCount; // name server records count
    private int arCount; // additional record count
    private int qr; // query or response
    private int opCode; // always 0 to represent a standard query (4 bits)
    private int AA; // indicates if authoritative (1 bit)
    private int TC; // indicates if response is truncated (1 bit)
    private int RD; // indicates if query wants the name server to answer
    // the question by initiating recursive query (1 bit)

    public DNSMessage() {
        this.RA = 0;
        this.Z = 0;
        this.RCODE = 0;
        this.queryId = new byte[2];
        this.qClass = 0;
        this.qType = 0;
        this.qName = "";
        this.qdCount = 0;
        this.anCount = 0;
        this.nsCount = 0;
        this.arCount = 0;
        this.qr = 0;
        this.opCode = 0;
        this.AA = 0;
        this.TC = 0;
        this.RD = 0;
    }
    public int getRA() {
        return RA;
    }

    public void setRA(int RA) {
        this.RA = RA;
    }

    public int getZ() {
        return Z;
    }

    public void setZ(int z) {
        Z = z;
    }

    public int getRCODE() {
        return RCODE;
    }

    public void setRCODE(int RCODE) {
        this.RCODE = RCODE;
    }

    public byte[] getQueryId() {
        return queryId;
    }

    public void setQueryId(byte[] queryId) {
        this.queryId = queryId;
    }

    public int getqClass() {
        return qClass;
    }

    public void setqClass(int qClass) {
        this.qClass = qClass;
    }

    public int getqType() {
        return qType;
    }

    public void setqType(int qType) {
        this.qType = qType;
    }

    public String getqName() {
        return qName;
    }

    public void setqName(String qName) {
        this.qName = qName;
    }

    public int getQdCount() {
        return qdCount;
    }

    public void setQdCount(int qdCount) {
        this.qdCount = qdCount;
    }

    public int getAnCount() {
        return anCount;
    }

    public void setAnCount(int anCount) {
        this.anCount = anCount;
    }

    public int getNsCount() {
        return nsCount;
    }

    public void setNsCount(int nsCount) {
        this.nsCount = nsCount;
    }

    public int getArCount() {
        return arCount;
    }

    public void setArCount(int arCount) {
        this.arCount = arCount;
    }

    public int getQr() {
        return qr;
    }

    public void setQr(int qr) {
        this.qr = qr;
    }

    public int getOpCode() {
        return opCode;
    }

    public void setOpCode(int opCode) {
        this.opCode = opCode;
    }

    public int getAA() {
        return AA;
    }

    public void setAA(int AA) {
        this.AA = AA;
    }

    public int getTC() {
        return TC;
    }

    public void setTC(int TC) {
        this.TC = TC;
    }

    public int getRD() {
        return RD;
    }

    public void setRD(int RD) {
        this.RD = RD;
    }



}
