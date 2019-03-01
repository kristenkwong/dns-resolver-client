package ca.ubc.cs.cs317.dnslookup;

import java.util.ArrayList;

/**
 * This class represents a DNS query/response.
 */

public class DNSMessage {
    private int RA; // recursion available (1 bit)
    private int Z; // reserved for future use - keep at 0 (3 bits)
    private int RCODE; // response code (4 bits)
    private int queryId; // query id (16 bits)
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

    // Variable number (qdCount) of Question records per DNS Message.
    ArrayList<DNSQuestionEntry> questions;
    
    // These are variable number of resource records.
    ArrayList<ResourceRecord> answerRRs;
    ArrayList<ResourceRecord> authorityRRs;
    ArrayList<ResourceRecord> additionalRRs;

    public DNSMessage() {
        this.RA = 0;
        this.Z = 0;
        this.RCODE = 0;
        this.queryId = 0;
        this.qdCount = 0;
        this.anCount = 0;
        this.nsCount = 0;
        this.arCount = 0;
        this.qr = 0;
        this.opCode = 0;
        this.AA = 0;
        this.TC = 0;
        this.RD = 0;
        this.questions = new ArrayList<DNSQuestionEntry>();
        this.answerRRs = new ArrayList<ResourceRecord>();
        this.authorityRRs = new ArrayList<ResourceRecord>();
        this.additionalRRs = new ArrayList<ResourceRecord>();
    }

    public void addQuestion(DNSQuestionEntry question) {
        this.questions.add(question);
    }

    public ArrayList<DNSQuestionEntry> getQuestions() {
        return this.questions;
    }

    public void addAnswerRR(ResourceRecord record) {
        this.answerRRs.add(record);
    }

    public ArrayList<ResourceRecord> getAnswerRRs() {
        return this.answerRRs;
    }

    public void addAuthorityRR(ResourceRecord record) {
        this.authorityRRs.add(record);
    }

    public ArrayList<ResourceRecord> getAuthorityRRs() {
        return this.authorityRRs;
    }

    public void addAdditionalRR(ResourceRecord record) {
        this.additionalRRs.add(record);
    }

    public ArrayList<ResourceRecord> getAdditionalRRs() {
        return this.additionalRRs;
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

    public int getQueryId() {
        return queryId;
    }

    public void setQueryId(int queryId) {
        this.queryId = queryId;
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
