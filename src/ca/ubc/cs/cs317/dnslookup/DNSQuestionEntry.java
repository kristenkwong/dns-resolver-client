package ca.ubc.cs.cs317.dnslookup;

/**
 * Class to hold a question section. A DNS message usually contains one question, but 
 * can contain more.
 */
public class DNSQuestionEntry {
    private String QNAME;
    private int QTYPE;
    private int QCLASS;

    public DNSQuestionEntry(String qname, int qtype, int qclass) {
        this.QNAME = qname;
        this.QTYPE = qtype;
        this.QCLASS = qclass;
    }

    public String getQname() {
        return this.QNAME;
    }
 
    public int getQtype() {
        return this.QTYPE;
    }

    public int getQclass() {
        return this.QCLASS;
    }

}