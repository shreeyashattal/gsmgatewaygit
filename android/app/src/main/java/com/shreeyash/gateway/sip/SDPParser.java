package com.shreeyash.gateway.sip;

import android.util.Log;

/**
 * SDP (Session Description Protocol) Parser
 * Extracts RTP connection information from SDP body
 */
public class SDPParser {
    private static final String TAG = "SDPParser";

    private String connectionAddress;
    private int audioPort;
    private int primaryCodec = 0; // Default to PCMU

    /**
     * Parse SDP body and extract connection info
     */
    public boolean parse(String sdp) {
        if (sdp == null || sdp.isEmpty()) {
            Log.e(TAG, "Empty SDP body");
            return false;
        }

        String[] lines = sdp.split("\r\n");
        if (lines.length == 1) {
            lines = sdp.split("\n"); // Try without \r
        }

        for (String line : lines) {
            line = line.trim();

            // Connection line: c=IN IP4 192.168.1.100
            if (line.startsWith("c=")) {
                parseConnectionLine(line);
            }
            // Media line: m=audio 10000 RTP/AVP 0 8
            else if (line.startsWith("m=audio")) {
                parseMediaLine(line);
            }
        }

        if (connectionAddress == null || audioPort == 0) {
            Log.e(TAG, "Failed to parse SDP: addr=" + connectionAddress + ", port=" + audioPort);
            return false;
        }

        Log.i(TAG, "Parsed SDP: " + connectionAddress + ":" + audioPort + ", codec=" + primaryCodec);
        return true;
    }

    /**
     * Parse connection line: c=IN IP4 192.168.1.100
     */
    private void parseConnectionLine(String line) {
        try {
            String[] parts = line.substring(2).split(" ");
            if (parts.length >= 3 && parts[0].equals("IN") && parts[1].equals("IP4")) {
                connectionAddress = parts[2].trim();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing connection line: " + line, e);
        }
    }

    /**
     * Parse media line: m=audio 10000 RTP/AVP 0 8 101
     */
    private void parseMediaLine(String line) {
        try {
            String[] parts = line.substring(2).split(" ");
            // parts[0] = "audio", parts[1] = port, parts[2] = "RTP/AVP", parts[3+] = codecs
            if (parts.length >= 4) {
                audioPort = Integer.parseInt(parts[1]);

                // First codec in the list is preferred
                if (parts.length > 3) {
                    primaryCodec = Integer.parseInt(parts[3]);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing media line: " + line, e);
        }
    }

    /**
     * Get the remote RTP address
     */
    public String getConnectionAddress() {
        return connectionAddress;
    }

    /**
     * Get the remote RTP port
     */
    public int getAudioPort() {
        return audioPort;
    }

    /**
     * Get the primary codec (0 = PCMU, 8 = PCMA)
     */
    public int getPrimaryCodec() {
        return primaryCodec;
    }

    /**
     * Check if using PCMU (G.711 u-law)
     */
    public boolean isPCMU() {
        return primaryCodec == 0;
    }

    /**
     * Check if using PCMA (G.711 a-law)
     */
    public boolean isPCMA() {
        return primaryCodec == 8;
    }

    @Override
    public String toString() {
        return "SDP{addr=" + connectionAddress + ", port=" + audioPort + ", codec=" + primaryCodec + "}";
    }
}
