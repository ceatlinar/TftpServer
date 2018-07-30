import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;

public class TFTPServer
{
    public static final int TFTPPORT = 4970;
    public static final int BUFSIZE = 516;
    public static final String READDIR = "C:\\tftp\\read\\";
    public static final String WRITEDIR = "C:\\tftp\\write\\";
    /* OP codes*/
    public static final int OP_RRQ = 1;
    public static final int OP_WRQ = 2;
    public static final int OP_DAT = 3;
    public static final int OP_ACK = 4;
    public static final int OP_ERR = 5;

    public static void main(String[] args) throws IOException
    {
        if (args.length > 0)
        {
            System.err.printf("usage: java %s\n", TFTPServer.class.getCanonicalName());
            System.exit(1);
        }
        /*Starting the server*/
        try
        {
            TFTPServer server= new TFTPServer();
            server.start();
        }
        catch (SocketException e)
        {e.printStackTrace();}
    }

    private void start() throws IOException
    {
        byte[] buf= new byte[BUFSIZE];

        /* Create socket*/
        DatagramSocket socket= new DatagramSocket(null);

        /* Create local bind point*/
        SocketAddress localBindPoint= new InetSocketAddress(TFTPPORT);
        socket.bind(localBindPoint);

        System.out.printf("Listening at port %d for new requests\n", TFTPPORT);

        /* Loop to handle client requests*/
        while (true)
        {

            final InetSocketAddress clientAddress = receiveFrom(socket, buf);

            /* If clientAddress is null, an error occurred in receiveFrom()*/
            if (clientAddress == null)
                continue;

            /*create Buffer Streams to get requested file from ParseRQ method and int to get request type*/
            final StringBuffer requestedFile = new StringBuffer();
            final StringBuffer mode = new StringBuffer();
            final int reqtype = ParseRQ(buf, requestedFile,mode);


            new Thread()
            {
                public void run()
                {
                    try
                    {
                        DatagramSocket sendSocket= new DatagramSocket(0);

                        /* Connect to client*/
                        sendSocket.connect(clientAddress);

                        System.out.printf("%s request for %s with %s mode from %s using port %d\n",
                                (reqtype == OP_RRQ)?"Read":"Write",requestedFile,mode,
                                clientAddress.getHostName(), clientAddress.getPort());

                        if (mode.toString().equals("octet"))
                        {
                            /*Read request*/
                            if (reqtype == OP_RRQ)
                            {
                                requestedFile.insert(0, READDIR);
                                HandleRQ(sendSocket, requestedFile.toString(), OP_RRQ, clientAddress);
                            }
                            /*Write request*/
                            else
                            {
                                requestedFile.insert(0, WRITEDIR);
                                HandleRQ(sendSocket,requestedFile.toString(),OP_WRQ,clientAddress);
                            }
                            sendSocket.close();
                        }
                        else
                        {
                            /*If mode is not octet, send error message 0*/
                            send_ERR(sendSocket,(short)0, "Attemp with unknown mode");
                        }
                    }
                    catch (IOException e)
                    {e.printStackTrace();}
                }
            }.start();
        }
    }

    /**
     * Reads the first block of data, i.e., the request for an action (read or write).
     * @param socket (socket to read from)
     * @param buf (where to store the read data)
     * @return socketAddress (the socket address of the client)
     */
    private InetSocketAddress receiveFrom(DatagramSocket socket, byte[] buf) throws IOException
    {
        /* Create datagram packet*/
        DatagramPacket receivePacket= new DatagramPacket(buf, buf.length);
        /* Receive packet*/
        socket.receive(receivePacket);
        /* Get client address and port from the packet*/
        InetSocketAddress clientSocket = new InetSocketAddress(receivePacket.getAddress(),receivePacket.getPort());
        return  clientSocket;
    }

    /**
     * Parses the request in buf to retrieve the type of request and requestedFile
     *
     * @param buf (received request)
     * @param requestedFile (name of file to read/write)
     * @return opcode (request type: RRQ or WRQ)
     */
    private int ParseRQ(byte[] buf, StringBuffer requestedFile, StringBuffer mode)
    {
        /*wrap the byte array*/
        ByteBuffer wrap= ByteBuffer.wrap(buf);
        /*get first short which is optcode*/
        short opcode = wrap.getShort();
        /*skip op code*/
        int i=2;

        /* get requested file name*/
        while(wrap.get(i)!=0)
        {
            requestedFile.append((char)wrap.get(i));
            i++;
        }

        /*skip zero byte*/
        i++;

        /*get mode*/
        while(wrap.get(i)!=0)
        {
            mode.append((char)wrap.get(i));
            i++;
        }
        return opcode;
    }

    /**
     * Handles RRQ and WRQ requests
     *
     * @param sendSocket (socket used to send/receive packets)
     * @param requestedFile (name of file to read/write)
     * @param opcode (RRQ or WRQ)
     */
    private void HandleRQ(DatagramSocket sendSocket, String requestedFile, int opcode, InetSocketAddress clientAddress) throws IOException
    {
        if(opcode == OP_RRQ)
        {
            File file = new File(requestedFile);
            /*If file doesnt exist send error message else create FileInputStream*/
            if(!file.exists())
            {
                send_ERR(sendSocket, (short) 1,"File Not Found");
            }

            else
            {
                /*create buffer to send packet*/
                byte[] bytesTOSend = new byte[516];
                /*Set first block number to 1*/
                short block=1;
                /*Set shortVal with Data opcode*/
                short shortVal=OP_DAT;
                /*Boolean variable to check the function sent the package*/
                boolean isSent=false;

                /*Create InputStream*/
                InputStream fileInput = null;
                /*Create File object*/

                fileInput = new FileInputStream(file);
                /*Get file length, in order to know how many packages you will send*/
                int  file_length = fileInput.available();
                for(int i=0;i<=file_length/512;i++)
                {
                    /*Create ack*/
                    createAck(bytesTOSend,shortVal,block);
                    /*Read 512 byte from file to send with buffer*/
                    fileInput.read(bytesTOSend, 4, 512);
                    /*If it is the last bytes read from file, send package with less than 516 bytes. Else, send exactly 516 bytes*/
                    if(block==file_length/512+1)
                    {
                        isSent =    send_DATA_receive_ACK(sendSocket, bytesTOSend, clientAddress,512,block);
                    }
                    else {
                        isSent =    send_DATA_receive_ACK(sendSocket, bytesTOSend, clientAddress,516, block);
                    }
                    if (isSent)
                    {
                        block++;
                        System.out.println("Transfered block number :"+block+" Total block number: "+((file_length/512)+1));
                    }
                    else
                    {
                        /*If same message is sent couple of times but hasn't receive ack send error number 0*/
                        send_ERR(sendSocket,(short)0,"No response from client");
                    }

                }

            }

        }

        else if (opcode == OP_WRQ)
        {
            /*Create file object*/
            File file = new File(requestedFile);
            if(file.exists())
            {
                send_ERR(sendSocket, (short) 6,"File Already Exists");
            }
            /*If it is a write request and file doesn't exist. Everything is fine, client can write the file*/
            else if(requestedFile.contains("root"))
            {
                /*If client try to create a file with a name that contains 'root', error is send*/
                send_ERR(sendSocket,(short)2,"Acces Violation, No such file can be created");
            }
            else
            {
                short block = 0;
                /*Create buffer for sending first ack to client*/
                byte[] sendBuffer = new byte[5];
                /*In order to know is it last package or not, check the return value from function*/
                int packagelength = 0;
                //create output stream
                OutputStream stream = new FileOutputStream(file);
                /*Create ack*/
                createAck(sendBuffer,(short)OP_ACK, (short) 0);
                //send client ack with block number zero to start transfer
                DatagramPacket sendPacket = new DatagramPacket(sendBuffer,4,clientAddress);
                sendSocket.send(sendPacket);

                /*Repeat receiving data and sending ack until last package is received*/
                do
                {
                    packagelength = receive_DATA_send_ACK(sendSocket,clientAddress, stream, block);
                    if (packagelength != 0)
                    {
                        block++;
                        System.out.println("Received block number :"+block);
                    }
                }while(packagelength != 512 & packagelength!= 0);

                if(packagelength == 0)
                {
                    /*If waited data hasnt been received from client*/
                    send_ERR(sendSocket,(short)0,"No data from client");
                }
            }
        }
        else
        {
            System.err.println("Invalid request. Sending an error packet.");
            send_ERR(sendSocket,(short)0, "Unknown request");
        }

    }


    private boolean send_DATA_receive_ACK( DatagramSocket sendSocket, byte[] bufSend, InetSocketAddress clientAddress, int length, short block) throws IOException
    {
        /* create acknowledge buffer*/
        byte[] ackBuf = new byte[5];
        short ackOpCode=-1;
        short ackBlock=0;
        boolean isok=false;
        int numOfTransmission=0;

        /*Send same package unless it is sent 4 times. If it is sent for time without getting ack for it, the connection will be killed*/
        while (numOfTransmission < 4 && !isok )
        {
            /*create and send packet*/
            DatagramPacket sendPacket = new DatagramPacket(bufSend,length,clientAddress);
            sendSocket.send(sendPacket);

            /*Send package and wait for ack for given time unless you get the ack before time expires
            * I implemented this to provide retransmission for a package if ack hasnt been received for a certain
            * amount of time*/
            DatagramPacket receivePacket = new DatagramPacket(ackBuf,ackBuf.length);
            sendSocket.setSoTimeout(250);
                try
                {
                    sendSocket.receive(receivePacket);
                }
                catch (SocketTimeoutException e)
                {
                    /*If ack isnt received, increase numoftransmission since the package will be send again*/
                    numOfTransmission++;
                }

                /*get ack opcode*/
                ackOpCode= getShort(ackBuf,0);
                /*get block number*/
                ackBlock= getShort(ackBuf,2);

            /*if ack opcode is 4 and block number is the one we sent then transfer is succesful
            * if ack opcode is 4 but block number isnt same, then it is the ack of another package
            * so this one will be send again, so increase numoftransmission*/
            if(ackOpCode == OP_ACK )
            {
                if(ackBlock == block)
                    isok=true;
                else
                    numOfTransmission++;
            }

            else if(ackOpCode == OP_ERR)
            {
                send_ERR(sendSocket,(short)0, "Connection is closed");
            }
            else
                numOfTransmission++;

        }
            /*if everything went okay and ack received for package, return true else return false*/
            if (isok)
                return true;
            else
                return false;
    }


     private int receive_DATA_send_ACK(DatagramSocket sendSocket, InetSocketAddress clientAddress, OutputStream stream, short previousblock) throws IOException
     {
         /*short values for getting opcode and block number from received package and buffer to send ack*/
         short receivedOpcode;
         short receivedBlock;
         byte[] sendBuffer = new byte[5];
         /*length is the value for return. If package is received then it is 516 or less based on final package or not
         * but if package isn't received, it'll remain as zero which will indicate error when it is gotten from this function in Handle_RQ function */
         int length = 0;
         /*Number of transmission is the total number of a package can be received plus how many times it is been waited but hasn't been received
         In this case the return value will be zero and after return, the connection will be closed but an error message will be sent before termination,
         * it is checked based */
         int numOfTransmission=0;
         /*Is the correct block number received or not*/
         boolean isok=false;
         /*DataGram packet to receive package*/
         DatagramPacket receivePacket;
         /*byte array to receive message into it*/
         byte[] receiveBuffer = new byte[516];


        while( numOfTransmission < 4 && !isok)
        {
            receivePacket= new DatagramPacket(receiveBuffer, receiveBuffer.length);
            /*Wait for the package for given time unless it is received before time expires*/
            sendSocket.setSoTimeout(2500);
            try
            {
                sendSocket.receive(receivePacket);
            }
            catch(SocketTimeoutException f)
            {
                numOfTransmission++;
            }

            /*Get opcode and block number*/
            receivedOpcode = getShort(receiveBuffer,0);
            receivedBlock = getShort(receiveBuffer,2);

            if (receivedOpcode == OP_DAT)
            {
                /*If package is data and block number is expected one get it and write to file and send client ack*/
                if (receivedBlock == (previousblock+1))
                {
                    isok = true;
                    previousblock++;
                    stream.write(receiveBuffer,4,receivePacket.getLength()-4);//write to the file
                    createAck(sendBuffer, (short) OP_ACK,receivedBlock);
                    DatagramPacket sendPacket = new DatagramPacket(sendBuffer, 4, clientAddress);//send it to client
                    sendSocket.send(sendPacket);
                }
                else
                {
                    numOfTransmission++;
                }
            }
            length = receivePacket.getLength();
        }

        return length;
    }


    private void send_ERR(DatagramSocket sendSocket,short errorNumber, String errorMessage) throws IOException
    {
        ByteBuffer error = ByteBuffer.allocate(errorMessage.length() + OP_ERR);
        error.putShort((short) OP_ERR);
        error.putShort(errorNumber);
        error.put(errorMessage.getBytes());

        DatagramPacket errorPacket = new DatagramPacket(error.array(), error.array().length);
        sendSocket.send(errorPacket);
    }

    /**
     * @param buf  buffer to get opcode or block number from
     * @param position index to know whether getting opcode or block number
     * @return the short value that we get from buffer
     */
    private short getShort(byte[] buf, int position)
    {
        ByteBuffer wrap= ByteBuffer.wrap(buf);
        short opt = wrap.getShort(position); //get opt code
        return opt;
    }

    private void createAck(byte[] buf, short opcode, short block)
    {
        ByteBuffer wrap = ByteBuffer.wrap(buf);
        wrap.putShort(opcode);
        wrap.putShort(block);
    }
}



