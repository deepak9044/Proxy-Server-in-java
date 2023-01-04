package proxyserver;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class Server
{
    public static void main(String[] args)
    {
       Server myProxy = new Server(8085);
        myProxy.listen();
    }

    private ServerSocket serverSocket;
    private AtomicBoolean running;

  
    private List<RequestHandler> serviceThreads;

    public Server(int port)
    {
        
        this.running = new AtomicBoolean(true);

        
        try {
            this.serverSocket = new ServerSocket(port);
        } catch(Exception e) {
            this.running.set(false);
            return;
        }

        
        this.serviceThreads = Collections.synchronizedList(new ArrayList<>());

        System.out.println("Waiting for client on port " + serverSocket.getLocalPort() + " ...");
    }

    public void listen()
    {
    	System.out.println(running.get());
        new Thread(() -> {
            while(running.get() && (serverSocket != null) && serverSocket.isBound())
            {
                try { serviceThreads.add(new RequestHandler(serverSocket.accept())); }
                catch(SocketTimeoutException e1) { /* Nothing to do here */ }
                catch (IOException e2) { e2.printStackTrace(); }
            }
        }).start();
    }

   

    private final class RequestHandler extends Thread
    {
        private final AtomicBoolean running;
        private final Socket socket;

        private BufferedReader proxyToClientInput;
        private BufferedWriter proxyToClientOutput;
        private Thread clientToServer, serverToClient;

        private RequestHandler(Socket requestSocket)
        {
            this.running = new AtomicBoolean(true);
            this.socket = requestSocket;

            try {
                //Setup timeout for disconnects
                this.socket.setSoTimeout(5000);

                //Setup input and output stream
                this.proxyToClientInput = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                this.proxyToClientOutput = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            } catch(Exception e) {
                this.running.set(false);
            }

            //Start itself
            this.start();
        }

        public void run()
        {
            try {
                //Read the first line for more information about the proxy type
                String[] request = (this.proxyToClientInput.readLine()).split(" ");
                System.out.println("Request: " + Arrays.toString(request));
                //Check for the right header length
                if(request.length < 3) return;

                //Check for connection type and call right function
                if(this.isHttpsTunnel(request[0])) this.connectTunnel(request[1]);
                else this.connectRelay(request[1]);

            } catch(Exception e) {
                this.closeConnection(this.socket, null);
            }
        }

        private boolean isHttpsTunnel(String conType)
        {
            return (conType.toLowerCase().equals("connect"));
        }

        private void connectTunnel(String remoteAddress)
        {
            //Get the RemoteAddress for Tunneling
            String remoteUrl = remoteAddress;
            int remotePort = 80;

            if(remoteAddress.contains(":"))
            {
                //Split the string for check of correct header
                String[] tempRemoteUrl = remoteAddress.split(":");

                //Check for right header
                if(tempRemoteUrl.length > 2) return;

                //Set remoteUrl and remotePort
                remotePort = Integer.valueOf(tempRemoteUrl[1]);
                remoteUrl = tempRemoteUrl[0];
            }


            try {
                Socket tempProxyToServer = new Socket(remoteUrl, remotePort);
                tempProxyToServer.setSoTimeout(5000);

                //Check if connection is possible
                if(!tempProxyToServer.isConnected())
                {
                    System.out.println("Can't connect to remote address.");

                    this.closeConnection(this.socket, null);
                    return ;
                }

               
                this.tunnelRequested();

                //Create ProxyToServerThread
                this.clientToServer = new RelayThread(this.socket, tempProxyToServer);
                this.serverToClient = new RelayThread(tempProxyToServer, this.socket);

                //Start bidirectional threads
                this.clientToServer.start();
                this.serverToClient.start();

                this.serverToClient.join();
                this.clientToServer.join();

                this.closeConnection(this.socket, tempProxyToServer);

            } catch (Exception e) {
                this.closeConnection(this.socket, null);
            }
        }

        private void connectRelay(String remoteAddress)
        {
            try {
               
                URL remoteURL = new URL(remoteAddress);

               
                HttpURLConnection proxyToServer = ((HttpURLConnection) remoteURL.openConnection());

               
                proxyToServer.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:64.0) Gecko/20100101 Firefox/64.0");
                proxyToServer.setRequestProperty("Content-Type",
                        "application/x-www-form-urlencoded");
                proxyToServer.setRequestProperty("Content-Language", "de-DE");

                //Setup connection
                proxyToServer.setUseCaches(false);
                proxyToServer.setDoOutput(true);

                if(proxyToServer.getResponseCode() != 200)
                {
                    this.notFoundSiteRequest();
                    this.closeConnection(this.socket, null);

                    return;
                }

                //Inform client of success
                this.relayRequested();

                //Send Server Callback to Client
                InputStream fromServer = proxyToServer.getInputStream();
                OutputStream toClient = this.socket.getOutputStream();

                byte[] buffer = new byte[4096];
                int read = 0;

                while(running.get() && !this.socket.isClosed() && (read >= 0))
                {
                    try {
                        read = fromServer.read(buffer);

                        if(read > 0)
                        {
                            toClient.write(buffer, 0, read);

                            if(fromServer.available() < 1)
                                toClient.flush();
                        }
                    } catch (Exception e1) {
                        /* Nothing to do here */
                    }
                }

                //Close connections
                this.closeConnection(this.socket, null);
                proxyToServer.disconnect();

            } catch(Exception e2) {
                e2.printStackTrace();
                this.closeConnection(this.socket, null);
            }
        }


        private void notFoundSiteRequest()
        {
            try {
                this.proxyToClientOutput.write(
                        "HTTP/1.0 404 NOT FOUND\n" +
                                "Proxy-agent: ProxyServer/1.0\n" +
                                "\r\n"
                );

                this.proxyToClientOutput.flush();
            } catch(Exception e) {
                e.printStackTrace();
            }
        }

        private void tunnelRequested()
        {
            try {
                this.proxyToClientOutput.write(
                        "HTTP/1.0 200 Connection established\r\n" +
                                "Proxy-Agent: ProxyServer/1.0\r\n" +
                                "\r\n"
                );

                this.proxyToClientOutput.flush();
            } catch(Exception e) {
                e.printStackTrace();
            }
        }

        private void relayRequested()
        {
            try {
                this.proxyToClientOutput.write(
                        "HTTP/1.0 200 OK\n" +
                                "Proxy-agent: ProxyServer/1.0\n" +
                                "\r\n"
                );

                this.proxyToClientOutput.flush();
            } catch(Exception e) {
                e.printStackTrace();
            }
        }

        private void closeConnection(Socket toClose1, Socket toClose2)
        {
            //Stop Loops
            this.running.set(false);

            //Close Streams
            try { if(this.proxyToClientInput != null) this.proxyToClientInput.close(); }
            catch(Exception e) { /* Nothing to do here */ }

            try { if(this.proxyToClientOutput != null) this.proxyToClientOutput.close(); }
            catch(Exception e) { /* Nothing to do here */ }

            //Close Sockets
            try { if(toClose1 != null) toClose1.close(); }
            catch(Exception e) { /* Nothing to do here */ }

            try { if(toClose2 != null) toClose2.close(); }
            catch(Exception e) { /* Nothing to do here */ }

            //Remove from connectionList
            serviceThreads.remove(this);
        }

        private final class RelayThread extends Thread
        {
            private final Socket fromSocket, toSocket;

            private RelayThread(Socket from, Socket to)
            {
                this.fromSocket = from;
                this.toSocket = to;
            }

            public void run()
            {
                //Get Packages from Server and Send to Client
                try {
                    InputStream fromServer = this.fromSocket.getInputStream();
                    OutputStream toClient = this.toSocket.getOutputStream();

                    byte[] buffer = new byte[4096];
                    int read = 0;

                    while(running.get() && !this.fromSocket.isClosed() && !this.toSocket.isClosed() && (read >= 0))
                    {
                        try {
                            read = fromServer.read(buffer);

                            if(read > 0)
                            {
                                toClient.write(buffer, 0, read);

                                if(fromServer.available() < 1)
                                    toClient.flush();
                            }

                        } catch(Exception e1) {
                            /* Nothing to do here */
                        }
                    }
                } catch(Exception e2) {
                    /* Nothing to do here */
                }

                //Stop the ProxyConnection
                
                closeConnection(fromSocket, toSocket);
            }
        }
    }
}
