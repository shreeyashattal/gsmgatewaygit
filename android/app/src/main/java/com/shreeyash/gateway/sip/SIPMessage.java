package com.shreeyash.gateway.sip;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SIP Message parser and builder
 * Handles SIP request/response creation and parsing
 */
public class SIPMessage {

    // Request line components (for requests)
    private String method;
    private String requestUri;
    private String sipVersion = "SIP/2.0";

    // Status line components (for responses)
    private int statusCode;
    private String reasonPhrase;

    // Headers
    private Map<String, String> headers = new HashMap<>();

    // Body (SDP)
    private String body;

    // Is this a request or response?
    private boolean isRequest;

    public SIPMessage() {}

    /**
     * Parse a SIP message from raw text
     */
    public static SIPMessage parse(String rawMessage) {
        SIPMessage msg = new SIPMessage();

        String[] parts = rawMessage.split("\r\n\r\n", 2);
        String headerSection = parts[0];
        if (parts.length > 1) {
            msg.body = parts[1];
        }

        String[] lines = headerSection.split("\r\n");
        if (lines.length == 0) return null;

        // Parse first line (request-line or status-line)
        String firstLine = lines[0].trim();

        // Skip empty or keep-alive messages (CRLF only, or just whitespace)
        if (firstLine.isEmpty() || firstLine.equals("\r\n") || firstLine.equals("\n")) {
            return null;
        }

        if (firstLine.startsWith("SIP/")) {
            // Response: SIP/2.0 200 OK
            msg.isRequest = false;
            String[] statusParts = firstLine.split(" ", 3);
            if (statusParts.length < 2) return null; // Malformed response
            msg.sipVersion = statusParts[0];
            try {
                msg.statusCode = Integer.parseInt(statusParts[1]);
            } catch (NumberFormatException e) {
                return null; // Malformed status code
            }
            msg.reasonPhrase = statusParts.length > 2 ? statusParts[2] : "";
        } else {
            // Request: INVITE sip:user@host SIP/2.0
            msg.isRequest = true;
            String[] requestParts = firstLine.split(" ");
            if (requestParts.length < 2) return null; // Malformed request
            msg.method = requestParts[0];
            msg.requestUri = requestParts[1];
            msg.sipVersion = requestParts.length > 2 ? requestParts[2] : "SIP/2.0";
        }

        // Parse headers
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                String key = line.substring(0, colonIndex).trim();
                String value = line.substring(colonIndex + 1).trim();
                msg.headers.put(key.toLowerCase(), value);
            }
        }

        return msg;
    }

    /**
     * Build a SIP REGISTER request
     */
    public static SIPMessage createRegister(String user, String domain, String localIp,
                                            int localPort, String callId, int cseq, int expires) {
        SIPMessage msg = new SIPMessage();
        msg.isRequest = true;
        msg.method = "REGISTER";
        msg.requestUri = "sip:" + domain;

        String contact = String.format("<sip:%s@%s:%d>", user, localIp, localPort);
        String from = String.format("<sip:%s@%s>;tag=%s", user, domain, generateTag());
        String to = String.format("<sip:%s@%s>", user, domain);
        String via = String.format("SIP/2.0/UDP %s:%d;branch=%s;rport",
                                   localIp, localPort, generateBranch());

        msg.headers.put("via", via);
        msg.headers.put("from", from);
        msg.headers.put("to", to);
        msg.headers.put("call-id", callId);
        msg.headers.put("cseq", cseq + " REGISTER");
        msg.headers.put("contact", contact + ";expires=" + expires);
        msg.headers.put("max-forwards", "70");
        msg.headers.put("user-agent", "GSM-Gateway/1.0");
        msg.headers.put("expires", String.valueOf(expires));
        msg.headers.put("allow", "INVITE,ACK,BYE,CANCEL,OPTIONS,INFO");
        msg.headers.put("content-length", "0");

        return msg;
    }

    /**
     * Build a SIP INVITE request
     */
    public static SIPMessage createInvite(String fromUser, String toUser, String domain,
                                          String localIp, int localPort, int rtpPort,
                                          String callId, int cseq) {
        SIPMessage msg = new SIPMessage();
        msg.isRequest = true;
        msg.method = "INVITE";
        msg.requestUri = "sip:" + toUser + "@" + domain;

        String fromTag = generateTag();
        String from = String.format("<sip:%s@%s>;tag=%s", fromUser, domain, fromTag);
        String to = String.format("<sip:%s@%s>", toUser, domain);
        String contact = String.format("<sip:%s@%s:%d>", fromUser, localIp, localPort);
        String via = String.format("SIP/2.0/UDP %s:%d;branch=%s;rport",
                                   localIp, localPort, generateBranch());

        msg.headers.put("via", via);
        msg.headers.put("from", from);
        msg.headers.put("to", to);
        msg.headers.put("call-id", callId);
        msg.headers.put("cseq", cseq + " INVITE");
        msg.headers.put("contact", contact);
        msg.headers.put("max-forwards", "70");
        msg.headers.put("user-agent", "GSM-Gateway/1.0");
        msg.headers.put("allow", "INVITE,ACK,BYE,CANCEL,OPTIONS,INFO");
        msg.headers.put("supported", "replaces,timer");

        // Create SDP body
        String sdp = createSDP(localIp, rtpPort);
        msg.body = sdp;
        msg.headers.put("content-type", "application/sdp");
        msg.headers.put("content-length", String.valueOf(sdp.length()));

        return msg;
    }

    /**
     * Build a SIP response
     */
    public static SIPMessage createResponse(SIPMessage request, int statusCode, String reasonPhrase) {
        SIPMessage msg = new SIPMessage();
        msg.isRequest = false;
        msg.statusCode = statusCode;
        msg.reasonPhrase = reasonPhrase;

        // Copy Via, From, To, Call-ID, CSeq from request
        msg.headers.put("via", request.getHeader("via"));
        msg.headers.put("from", request.getHeader("from"));
        msg.headers.put("to", request.getHeader("to"));
        msg.headers.put("call-id", request.getHeader("call-id"));
        msg.headers.put("cseq", request.getHeader("cseq"));
        msg.headers.put("user-agent", "GSM-Gateway/1.0");
        msg.headers.put("content-length", "0");

        return msg;
    }

    /**
     * Build a 200 OK response with SDP
     */
    public static SIPMessage createOkWithSDP(SIPMessage request, String localIp,
                                              int localPort, int rtpPort, String toTag) {
        SIPMessage msg = new SIPMessage();
        msg.isRequest = false;
        msg.statusCode = 200;
        msg.reasonPhrase = "OK";

        // Copy headers from request
        msg.headers.put("via", request.getHeader("via"));
        msg.headers.put("from", request.getHeader("from"));

        // Add to-tag to To header
        String to = request.getHeader("to");
        if (!to.contains("tag=")) {
            to = to + ";tag=" + toTag;
        }
        msg.headers.put("to", to);

        msg.headers.put("call-id", request.getHeader("call-id"));
        msg.headers.put("cseq", request.getHeader("cseq"));

        String contact = String.format("<sip:%s:%d>", localIp, localPort);
        msg.headers.put("contact", contact);
        msg.headers.put("user-agent", "GSM-Gateway/1.0");
        msg.headers.put("allow", "INVITE,ACK,BYE,CANCEL,OPTIONS,INFO");

        // Create SDP body
        String sdp = createSDP(localIp, rtpPort);
        msg.body = sdp;
        msg.headers.put("content-type", "application/sdp");
        msg.headers.put("content-length", String.valueOf(sdp.length()));

        return msg;
    }

    /**
     * Build a SIP ACK request
     */
    public static SIPMessage createAck(SIPMessage inviteResponse, String localIp, int localPort) {
        SIPMessage msg = new SIPMessage();
        msg.isRequest = true;
        msg.method = "ACK";

        // Get request URI from Contact header of response, or use To header
        String contact = inviteResponse.getHeader("contact");
        if (contact != null && contact.contains("<") && contact.contains(">")) {
            msg.requestUri = contact.substring(contact.indexOf('<') + 1, contact.indexOf('>'));
        } else {
            String to = inviteResponse.getHeader("to");
            if (to.contains("<")) {
                msg.requestUri = to.substring(to.indexOf('<') + 1, to.indexOf('>'));
            } else {
                msg.requestUri = to;
            }
        }

        String via = String.format("SIP/2.0/UDP %s:%d;branch=%s;rport",
                                   localIp, localPort, generateBranch());

        msg.headers.put("via", via);
        msg.headers.put("from", inviteResponse.getHeader("from"));
        msg.headers.put("to", inviteResponse.getHeader("to"));
        msg.headers.put("call-id", inviteResponse.getHeader("call-id"));

        // ACK CSeq must have same number as INVITE
        String cseq = inviteResponse.getHeader("cseq");
        String cseqNum = cseq.split(" ")[0];
        msg.headers.put("cseq", cseqNum + " ACK");

        msg.headers.put("max-forwards", "70");
        msg.headers.put("content-length", "0");

        return msg;
    }

    /**
     * Build a SIP BYE request
     */
    public static SIPMessage createBye(String callId, String from, String to,
                                       String remoteUri, String localIp, int localPort, int cseq) {
        SIPMessage msg = new SIPMessage();
        msg.isRequest = true;
        msg.method = "BYE";
        msg.requestUri = remoteUri;

        String via = String.format("SIP/2.0/UDP %s:%d;branch=%s;rport",
                                   localIp, localPort, generateBranch());

        msg.headers.put("via", via);
        msg.headers.put("from", from);
        msg.headers.put("to", to);
        msg.headers.put("call-id", callId);
        msg.headers.put("cseq", cseq + " BYE");
        msg.headers.put("max-forwards", "70");
        msg.headers.put("content-length", "0");

        return msg;
    }

    /**
     * Create SDP for audio (G.711 u-law)
     */
    private static String createSDP(String ip, int port) {
        String sessionId = String.valueOf(System.currentTimeMillis());
        StringBuilder sdp = new StringBuilder();
        sdp.append("v=0\r\n");
        sdp.append("o=GSMGateway ").append(sessionId).append(" ").append(sessionId)
           .append(" IN IP4 ").append(ip).append("\r\n");
        sdp.append("s=GSM Gateway Call\r\n");
        sdp.append("c=IN IP4 ").append(ip).append("\r\n");
        sdp.append("t=0 0\r\n");
        sdp.append("m=audio ").append(port).append(" RTP/AVP 0 8 101\r\n");
        sdp.append("a=rtpmap:0 PCMU/8000\r\n");
        sdp.append("a=rtpmap:8 PCMA/8000\r\n");
        sdp.append("a=rtpmap:101 telephone-event/8000\r\n");
        sdp.append("a=fmtp:101 0-16\r\n");
        sdp.append("a=ptime:20\r\n");
        sdp.append("a=sendrecv\r\n");
        return sdp.toString();
    }

    /**
     * Generate a random tag for From/To headers
     */
    public static String generateTag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Generate a branch parameter for Via header
     */
    public static String generateBranch() {
        return "z9hG4bK" + UUID.randomUUID().toString().substring(0, 12);
    }

    /**
     * Generate a unique Call-ID
     */
    public static String generateCallId(String domain) {
        return UUID.randomUUID().toString() + "@" + domain;
    }

    /**
     * Serialize message to string
     */
    public String toBytes() {
        StringBuilder sb = new StringBuilder();

        // First line
        if (isRequest) {
            sb.append(method).append(" ").append(requestUri).append(" ").append(sipVersion).append("\r\n");
        } else {
            sb.append(sipVersion).append(" ").append(statusCode).append(" ").append(reasonPhrase).append("\r\n");
        }

        // Headers (in preferred order)
        String[] orderedHeaders = {"via", "from", "to", "call-id", "cseq", "contact",
                                   "max-forwards", "user-agent", "allow", "supported",
                                   "expires", "authorization", "www-authenticate",
                                   "content-type", "content-length"};

        for (String key : orderedHeaders) {
            String value = headers.get(key);
            if (value != null) {
                sb.append(capitalizeHeader(key)).append(": ").append(value).append("\r\n");
            }
        }

        // Any remaining headers
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey();
            boolean alreadyWritten = false;
            for (String ordered : orderedHeaders) {
                if (ordered.equals(key)) {
                    alreadyWritten = true;
                    break;
                }
            }
            if (!alreadyWritten) {
                sb.append(capitalizeHeader(key)).append(": ").append(entry.getValue()).append("\r\n");
            }
        }

        // Blank line
        sb.append("\r\n");

        // Body
        if (body != null) {
            sb.append(body);
        }

        return sb.toString();
    }

    private String capitalizeHeader(String header) {
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = true;
        for (char c : header.toCharArray()) {
            if (c == '-') {
                result.append(c);
                capitalizeNext = true;
            } else if (capitalizeNext) {
                result.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    // Getters
    public boolean isRequest() { return isRequest; }
    public String getMethod() { return method; }
    public String getRequestUri() { return requestUri; }
    public int getStatusCode() { return statusCode; }
    public String getReasonPhrase() { return reasonPhrase; }
    public String getHeader(String name) { return headers.get(name.toLowerCase()); }
    public String getBody() { return body; }
    public Map<String, String> getHeaders() { return headers; }

    // Setters
    public void setHeader(String name, String value) { headers.put(name.toLowerCase(), value); }
    public void setBody(String body) { this.body = body; }

    /**
     * Extract the number being called from request URI
     */
    public String getDialedNumber() {
        if (requestUri == null) return null;
        // sip:1234567890@domain -> 1234567890
        String uri = requestUri;
        if (uri.startsWith("sip:")) {
            uri = uri.substring(4);
        }
        int atIndex = uri.indexOf('@');
        if (atIndex > 0) {
            return uri.substring(0, atIndex);
        }
        return uri;
    }

    /**
     * Get the Call-ID
     */
    public String getCallId() {
        return getHeader("call-id");
    }

    /**
     * Get From tag
     */
    public String getFromTag() {
        String from = getHeader("from");
        if (from == null) return null;
        int tagIndex = from.indexOf("tag=");
        if (tagIndex < 0) return null;
        int endIndex = from.indexOf(';', tagIndex + 4);
        if (endIndex < 0) endIndex = from.length();
        return from.substring(tagIndex + 4, endIndex);
    }

    /**
     * Get To tag
     */
    public String getToTag() {
        String to = getHeader("to");
        if (to == null) return null;
        int tagIndex = to.indexOf("tag=");
        if (tagIndex < 0) return null;
        int endIndex = to.indexOf(';', tagIndex + 4);
        if (endIndex < 0) endIndex = to.length();
        return to.substring(tagIndex + 4, endIndex);
    }
}
