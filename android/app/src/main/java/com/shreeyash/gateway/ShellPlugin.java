package com.shreeyash.gateway;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.JSObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

@CapacitorPlugin(name = "Shell")
public class ShellPlugin extends Plugin {

    @PluginMethod
    public void echo(PluginCall call) {
        String value = call.getString("value");
        JSObject ret = new JSObject();
        ret.put("value", value);
        call.resolve(ret);
    }

    @PluginMethod
    public void execute(PluginCall call) {
        String command = call.getString("command");
        boolean asRoot = call.getBoolean("asRoot", false);
        
        if (command == null) {
            call.reject("Command must be provided");
            return;
        }

        new Thread(() -> {
            Process process = null;
            DataOutputStream os = null;
            BufferedReader stdoutReader = null;
            BufferedReader stderrReader = null;
            
            try {
                if (asRoot) {
                    // Request root access - this will trigger Magisk prompt
                    process = Runtime.getRuntime().exec("su");
                    os = new DataOutputStream(process.getOutputStream());
                    
                    // Write command to su shell
                    os.writeBytes(command + "\n");
                    os.writeBytes("exit\n");
                    os.flush();
                    os.close();
                    os = null;
                } else {
                    // Execute as regular user
                    process = Runtime.getRuntime().exec(command);
                }
                
                // Read stdout
                stdoutReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                StringBuilder output = new StringBuilder();
                String line;
                while ((line = stdoutReader.readLine()) != null) {
                    if (output.length() > 0) output.append("\n");
                    output.append(line);
                }
                
                // Read stderr for error messages
                stderrReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
                StringBuilder errorOutput = new StringBuilder();
                while ((line = stderrReader.readLine()) != null) {
                    if (errorOutput.length() > 0) errorOutput.append("\n");
                    errorOutput.append(line);
                }
                
                // Wait for process with timeout (30 seconds for root commands, 10 for regular)
                // This gives time for Magisk prompt to appear and user to approve
                boolean finished = process.waitFor(asRoot ? 30 : 10, TimeUnit.SECONDS);
                
                if (!finished) {
                    process.destroyForcibly();
                    call.reject("Command execution timeout. Root permission may not have been granted. Please check Magisk.");
                    return;
                }
                
                int exitCode = process.exitValue();
                
                // If root command failed, check if it's a permission denial
                if (asRoot && exitCode != 0) {
                    String errorMsg = errorOutput.toString().toLowerCase();
                    if (errorMsg.contains("permission denied") || 
                        errorMsg.contains("not allowed") || 
                        errorMsg.contains("access denied") ||
                        errorMsg.contains("su: must be suid to work properly")) {
                        call.reject("Root permission denied. Please grant SuperUser access in Magisk.");
                        return;
                    }
                }
                
                JSObject ret = new JSObject();
                ret.put("output", output.toString());
                ret.put("exitCode", exitCode);
                
                // Include stderr in response for debugging
                if (errorOutput.length() > 0) {
                    ret.put("error", errorOutput.toString());
                }
                
                call.resolve(ret);
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                call.reject("Command execution interrupted: " + e.getMessage());
            } catch (IOException e) {
                if (asRoot) {
                    // If su command itself fails, device might not be rooted or su not available
                    call.reject("Root access unavailable. Error: " + e.getMessage() + 
                               ". Please ensure device is rooted and Magisk is installed.");
                } else {
                    call.reject("Command execution failed: " + e.getMessage());
                }
            } catch (Exception e) {
                call.reject("Unexpected error: " + e.getMessage());
            } finally {
                // Clean up resources
                try {
                    if (stdoutReader != null) stdoutReader.close();
                    if (stderrReader != null) stderrReader.close();
                    if (os != null) os.close();
                    if (process != null) {
                        process.destroy();
                        if (process.isAlive()) {
                            process.destroyForcibly();
                        }
                    }
                } catch (IOException e) {
                    // Ignore cleanup errors
                }
            }
        }).start();
    }
}
