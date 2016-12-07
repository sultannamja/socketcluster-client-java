package io.github.sac;

import com.neovisionaries.ws.client.*;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Created by sachin on 13/11/16.
 */

public class Socket extends Emitter {


    private final static Logger LOGGER = Logger.getLogger(Socket.class.getName());

    private AtomicInteger counter;
    private String URL;
    private WebSocketFactory factory;
    private ReconnectStrategy strategy;
    private WebSocket ws;
    private BasicListener listener;
    private String AuthToken;
    private HashMap <Long,Object[]> acks;
    private List <Channel> channels;
    private WebSocketAdapter adapter;

    public Socket(String URL) {
        this.URL = URL;
        factory=new WebSocketFactory().setConnectionTimeout(5000);
        counter=new AtomicInteger(1);
        acks= new HashMap<>();
        channels= new ArrayList<>();
        adapter=getAdapter();
    }

    public Channel createChannel(String name){
        Channel channel=new Channel(name);
        channels.add(channel);
        return channel;
    }

    public List<Channel> getChannels() {
        return channels;
    }

    public Channel getChannelByName(String name){
        for (Channel channel:channels){
            if (channel.getChannelName().equals(name))
                return channel;
        }
        return null;
    }

    public void seturl(String url){
        this.URL=url;
    }

    public void setReconnection(ReconnectStrategy strategy) {
        this.strategy = strategy;
    }

    public void setListener(BasicListener listener){
        this.listener=listener;
    }

    /**
     * used to set up TLS/SSL connection to server for more details visit neovisionaries websocket client
     * @return
     */

    public WebSocketFactory getFactorySettings(){
        return factory;
    }

    public void setAuthToken(String token){
        AuthToken=token;
    }

    public WebSocketAdapter getAdapter(){
        return new WebSocketAdapter(){

            @Override
            public void onConnected(WebSocket websocket, Map<String, List<String>> headers) throws Exception {

                /**
                 * Code for sending handshake
                 */

                counter.set(1);
                if (strategy!=null)
                strategy.setAttmptsMade(0);

                JSONObject handshakeObject=new JSONObject();
                handshakeObject.put("event","#handshake");
                JSONObject object=new JSONObject();
                object.put("authToken",AuthToken);
                handshakeObject.put("data",object);
                handshakeObject.put("cid",counter.getAndIncrement());
                websocket.sendText(handshakeObject.toJSONString());

//                websocket.sendText("{\"event\": \"#handshake\",\"data\": {\"authToken\":\""+AuthToken+"\"},\"cid\": "+ cid++ +"}");

                listener.onConnected(Socket.this,headers);

                super.onConnected(websocket, headers);
            }

            @Override
            public void onDisconnected(WebSocket websocket, WebSocketFrame serverCloseFrame, WebSocketFrame clientCloseFrame, boolean closedByServer) throws Exception {
                listener.onDisconnected(Socket.this,serverCloseFrame,clientCloseFrame,closedByServer);
                if (strategy!=null) {
                    reconnect();
                }else{
//                    System.out.println("cant reconnect , reconnection is null");
                    LOGGER.info("cant reconnect , reconnection is null");
                }
                super.onDisconnected(websocket, serverCloseFrame, clientCloseFrame, closedByServer);
            }

            @Override
            public void onConnectError(WebSocket websocket, WebSocketException exception) throws Exception {
                listener.onConnectError(Socket.this,exception);
                if (strategy!=null) {
                    reconnect();
                }else{
//                    System.out.println("cant reconnect , reconnection is null");
                    LOGGER.info("cant reconnect , reconnection is null");

                }
                super.onConnectError(websocket, exception);
            }


            @Override
            public void onFrame(WebSocket websocket, WebSocketFrame frame) throws Exception {
//                System.out.println("On frame got called :"+frame.getPayloadText());


                if (frame.getPayloadText().equalsIgnoreCase("#1")) {
                    /**
                     *  PING-PONG logic goes here
                     */
                    websocket.sendText("#2");
                }else {
                    JSONParser parser = new JSONParser();
                    JSONObject object= (JSONObject) parser.parse(frame.getPayloadText());

                    /**
                     * Message retrieval mechanism goes here
                     */
                    LOGGER.info("Message :"+object.toJSONString());

                    Object dataobject = object.get("data");
                    Long rid = (Long) object.get("rid");
                    Long cid = (Long) object.get("cid");
                    String event = (String) object.get("event");


                    switch (Parser.parse(dataobject,rid,cid,event)) {

                        case ISAUTHENTICATED:
                            listener.onAuthentication(Socket.this,(Boolean) ((JSONObject)dataobject).get("isAuthenticated"));
                            subscribeChannels();
                            break;
                        case PUBLISH:
                            Socket.this.handlePublish(String.valueOf((((JSONObject)dataobject).get("channel"))), ((JSONObject)dataobject).get("data"));
                            break;
                        case REMOVETOKEN:
                            setAuthToken(null);
                            break;
                        case SETTOKEN:
                            listener.onSetAuthToken((String) ((JSONObject)dataobject).get("token"),Socket.this);
                            break;
                        case EVENT:
                            if (hasEventAck(event)) {
//                                System.out.println("This event has ack");
//                                LOGGER.info("This event have ack");
                                handleEmitAck(event,dataobject,ack(cid));
                            }else {
                                Socket.this.handleEmit(event, dataobject);
//                                System.out.println("This ack doesnt have ack");
//                                LOGGER.info("This event doesn't have ack");

                            }
                            break;
                        case ACKRECEIVE:

                            if (acks.containsKey(rid)) {
//                                System.out.println("Contains ack with id "+rid);
                                Object[] objects = acks.remove(rid);
                                if (objects != null) {
                                    Ack fn = (Ack) objects[1];
                                    if (fn != null) {
//                                    System.out.println("calling fun with ack"+rid);
                                        fn.call((String) objects[0],object.get("error"), object.get("data"));
                                    } else {
//                                    System.out.println("ack function is null with rid "+rid);
                                        LOGGER.info("ack function is null with rid " + rid);
                                    }
                                }
                            }
                            break;
                    }

                }
                super.onFrame(websocket, frame);
            }


            @Override
            public void onCloseFrame(WebSocket websocket, WebSocketFrame frame) throws Exception {
//                System.out.println("On close frame got called");
                LOGGER.info("On close frame got called");

                super.onCloseFrame(websocket, frame);
            }

            @Override
            public void onSendError(WebSocket websocket, WebSocketException cause, WebSocketFrame frame) throws Exception {
//                System.out.println("Got send error");
                LOGGER.info("Got send error");

                super.onSendError(websocket, cause, frame);
            }

        };

    }
    public Socket emit(final String event, final Object object){
        EventThread.exec(new Runnable() {
            public void run() {
                JSONObject eventObject=new JSONObject();
                eventObject.put("event",event);
                eventObject.put("data",object);
//                eventObject.put("cid",counter.getAndIncrement());
                ws.sendText(eventObject.toJSONString());
            }
        });
//        socket.sendText("{\"event\":\""+eventname+"\",\"data\":\""+data+"\",\"cid\":"+ cid++ +"}");
        return this;
    }


    public Socket emit(final String event, final Object object, final Ack ack){

        EventThread.exec(new Runnable() {
            public void run() {
                JSONObject eventObject=new JSONObject();
                acks.put(counter.longValue(),getAckObject(event,ack));
                eventObject.put("event",event);
                eventObject.put("data",object);
                eventObject.put("cid",counter.getAndIncrement());
                ws.sendText(eventObject.toJSONString());
            }
        });
        return this;
    }

    public Socket subscribe(final String channel){
        EventThread.exec(new Runnable() {
            public void run() {
                JSONObject subscribeObject=new JSONObject();
                subscribeObject.put("event","#subscribe");
                JSONObject object=new JSONObject();
                object.put("channel",channel);
                subscribeObject.put("data",object);

                subscribeObject.put("cid",counter.getAndIncrement());
                ws.sendText(subscribeObject.toJSONString());
            }
        });
//        ws.sendText("{\"event\":\"#subscribe\",\"data\":{\"channel\":\""+channel+"\"},\"cid\":"+cid++ +"}");
        return this;
    }

    public Object[] getAckObject(String event,Ack ack){
        Object object[]={event,ack};
        return object;
    }

    public Socket subscribe(final String channel, final Ack ack){
        EventThread.exec(new Runnable() {
            public void run() {
                JSONObject subscribeObject=new JSONObject();
                subscribeObject.put("event","#subscribe");
                JSONObject object=new JSONObject();
                acks.put(counter.longValue(),getAckObject(channel,ack));

                object.put("channel",channel);
                subscribeObject.put("data",object);
                subscribeObject.put("cid",counter.getAndIncrement());
                ws.sendText(subscribeObject.toJSONString());
            }
        });
//        ws.sendText("{\"event\":\"#subscribe\",\"data\":{\"channel\":\""+channel+"\"},\"cid\":"+cid++ +"}");
        return this;
    }

    public Socket unsubscribe(final String channel){
        EventThread.exec(new Runnable() {
            public void run() {
                JSONObject subscribeObject=new JSONObject();
                subscribeObject.put("event","#unsubscribe");
                subscribeObject.put("data",channel);
                subscribeObject.put("cid",counter.getAndIncrement());
                ws.sendText(subscribeObject.toJSONString());
            }
        });
        return this;
    }

    public Socket unsubscribe(final String channel,final Ack ack){
        EventThread.exec(new Runnable() {
            public void run() {
                JSONObject subscribeObject=new JSONObject();
                subscribeObject.put("event","#unsubscribe");
                subscribeObject.put("data",channel);

                acks.put(counter.longValue(),getAckObject(channel,ack));
                subscribeObject.put("cid",counter.getAndIncrement());
                ws.sendText(subscribeObject.toJSONString());
            }
        });
        return this;
    }

    public Socket publish (final String channel, final Object data){
        EventThread.exec(new Runnable() {
            public void run() {
                JSONObject publishObject=new JSONObject();
                publishObject.put("event","#publish");
                JSONObject object=new JSONObject();
                object.put("channel",channel);
                object.put("data",data);
                publishObject.put("data",object);
                publishObject.put("cid",counter.getAndIncrement());
                ws.sendText(publishObject.toJSONString());
            }
        });

//        ws.sendText("{\"event\":\"#publish\",\"data\":{\"channel\":\""+channel+"\",\"data\":\""+data+"\"}}");
        return this;
    }

    public Socket publish (final String channel, final Object data,final  Ack ack){
        EventThread.exec(new Runnable() {
            public void run() {
                JSONObject publishObject=new JSONObject();
                publishObject.put("event","#publish");
                JSONObject object=new JSONObject();
                acks.put(counter.longValue(),getAckObject(channel,ack));
                object.put("channel",channel);
                object.put("data",data);
                publishObject.put("data",object);
                publishObject.put("cid",counter.getAndIncrement());
                ws.sendText(publishObject.toJSONString());
            }
        });

//        ws.sendText("{\"event\":\"#publish\",\"data\":{\"channel\":\""+channel+"\",\"data\":\""+data+"\"}}");
        return this;
    }

    public Ack ack(final Long cid){
        return new Ack() {
            public void call(final String channel,final Object error, final Object data) {
                EventThread.exec(new Runnable() {
                    public void run() {
                        JSONObject object=new JSONObject();
                        object.put("error",error);
                        object.put("data",data);
                        object.put("rid",cid);
                        ws.sendText(object.toJSONString());
                    }
                });
            }
        };
    }


    private void subscribeChannels(){
        for (Channel channel:channels){
            channel.subscribe();
        }
    }

    public void connect() {

        try {
            ws = factory.createSocket(URL);
        }catch (IOException e){
            e.printStackTrace();
        }
        ws.addExtension("permessage-deflate; client_max_window_bits");
        ws.addHeader("Accept-Encoding","gzip, deflate, sdch");
        ws.addHeader("Accept-Language","en-US,en;q=0.8");
        ws.addHeader("Pragma","no-cache");
        ws.addHeader("User-Agent","Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/49.0.2623.87 Safari/537.36");

        ws.addListener(adapter);

        try {
            ws.connect();
        }catch (OpeningHandshakeException e)
        {
            // A violation against the WebSocket protocol was detected
            // during the opening handshake.

            // Status line.
            StatusLine sl = e.getStatusLine();
            LOGGER.info("=== Status Line ===");
            LOGGER.info("HTTP Version  = \n"+sl.getHttpVersion());
            LOGGER.info("Status Code   = \n"+ sl.getStatusCode());
            LOGGER.info("Reason Phrase = \n"+ sl.getReasonPhrase());

            // HTTP headers.
            Map<String, List<String>> headers = e.getHeaders();
            LOGGER.info("=== HTTP Headers ===");
            for (Map.Entry<String, List<String>> entry : headers.entrySet())
            {
                // Header name.
                String name = entry.getKey();

                // Values of the header.
                List<String> values = entry.getValue();

                if (values == null || values.size() == 0)
                {
                    // Print the name only.
                    LOGGER.info(name);
                    continue;
                }

                for (String value : values)
                {
                    // Print the name and the value.
                    LOGGER.info(name+value+"\n");
                }
            }
        }
        catch (WebSocketException e)
        {
            // Failed to establish a WebSocket connection.
            listener.onConnectError(Socket.this,e);
            if (strategy!=null) {
                reconnect();
            }else{
                LOGGER.info("cant reconnect , reconnection is null");
            }
        }

    }


    public void reconnect(){

//        if (!strategy.areAttemptsComplete()) {
//            strategy.setListener(new io.github.sac.ReconnectStrategy.Callback() {
//                public void connect()  {
//                    System.out.println("reconnect got called");
//                    io.github.sac.Socket.this.connect();
//                }
//            });
//        }else{
//            System.out.println("Attempts are complete");
//        }

        if (!strategy.areAttemptsComplete()) {

            final Timer timer=new Timer();
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    strategy.processValues();
                    Socket.this.connect();
                    timer.cancel();
                    timer.purge();
                }
            },strategy.getReconnectInterval());

        }

    }

    public void disconnect(){
        ws.disconnect();
        strategy=null;
    }

    /**
     * States can be
     * CLOSED
     * CLOSING
     * CONNECTING
     * CREATED
     * OPEN
     * @return
     */

    public WebSocketState getCurrentState(){
        return ws.getState();
    }

    public Boolean isconnected(){
        return ws.getState()==WebSocketState.OPEN;
    }

    public void disableLogging(){
        LogManager.getLogManager().reset();
    }
    /**
     * Channels need to be subscribed everytime whenever client is reconnected to server (handled inside)
     * Add only one listener to one channel for whole lifetime of process
     */

    public class Channel{

        String channelName;

        public String getChannelName() {
            return channelName;
        }

        public Channel(String channelName) {
            this.channelName = channelName;
        }

        public void subscribe(){
            Socket.this.subscribe(channelName);
        }

        public void subscribe(Ack ack){
            Socket.this.subscribe(channelName,ack);
        }

        public void onMessage(Listener listener){
            Socket.this.onSubscribe(channelName,listener);
        }

        public void publish(Object data){
            Socket.this.publish(channelName,data);
        }

        public void publish(Object data,Ack ack){
            Socket.this.publish(channelName,data,ack);
        }

        public void unsubscribe(){
            Socket.this.unsubscribe(channelName);
            channels.remove(this);
        }

        public void unsubscribe(Ack ack){
            Socket.this.unsubscribe(channelName,ack);
            channels.remove(this);
        }
    }
}
