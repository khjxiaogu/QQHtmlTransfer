package com.khjxiaogu.HtmlMessageTransfer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.KeyManagerFactory;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.khjxiaogu.webserver.BasicWebServerBuilder;
import com.khjxiaogu.webserver.web.CallBack;
import com.khjxiaogu.webserver.web.lowlayer.Request;
import com.khjxiaogu.webserver.web.lowlayer.Response;
import com.khjxiaogu.webserver.web.lowlayer.WebsocketEvents;

import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.concurrent.UnorderedThreadPoolEventExecutor;
import net.mamoe.mirai.Bot;
import net.mamoe.mirai.console.plugins.Config;
import net.mamoe.mirai.console.plugins.ConfigSection;
import net.mamoe.mirai.console.plugins.PluginBase;
import net.mamoe.mirai.contact.Member;
import net.mamoe.mirai.event.events.MessageRecallEvent.GroupRecall;
import net.mamoe.mirai.message.GroupMessageEvent;
import net.mamoe.mirai.message.data.*;

public class HtmlMessageTransfer extends PluginBase implements WebsocketEvents,CallBack {
	Config config;
	long group;
	class LimitedQueue<E> extends LinkedList<E> {

		private static final long serialVersionUID = -385948333811091660L;
		private int limit;

	    public LimitedQueue(int limit) {
	        this.limit = limit;
	    }

	    @Override
	    public boolean add(E o) {
	        boolean added = super.add(o);
	        while (added && size() > limit) {
	           super.remove();
	        }
	        return added;
	    }
	}
	@FunctionalInterface
	private interface Handler<T extends Message>{
		public void handle(T msg,StringBuilder appender,Bot objthis) ;
	}
	private class HandlerContext<T extends Message>{
		Class<T> cls;
		Handler<T> handler;
		
		public HandlerContext(Class<T> cls, Handler<T> handler) {
			this.cls = cls;
			this.handler = handler;
		}
		@SuppressWarnings("unchecked")
		public boolean handle(Message msg,StringBuilder appender,Bot objthis) {
			if(cls.isInstance(msg)) {
				handler.handle((T) msg, appender,objthis);
				return true;
			}
			return false;
		};
	}
	private List<HandlerContext<?>> handlers=new ArrayList<>();
	private void handle(Message msg,StringBuilder appender,Bot objthis) {
		for(HandlerContext<?> ctx:handlers) {
			if(ctx.handle(msg, appender,objthis))
				return;
		}
		appender.append(msg.contentToString());
	}
	private StringBuilder handle(Message msg,Bot objthis) {
		StringBuilder appender=new StringBuilder();
		for(HandlerContext<?> ctx:handlers) {
			if(ctx.handle(msg, appender,objthis))
				return appender;
		}
		appender.append(msg.contentToString());
		return appender;
	}
	private <T extends Message> void addHandler(Class<T> cls, Handler<T> handler) {
		handlers.add(new HandlerContext<T>(cls,handler));
	}
	private String getName(Member m) {
		String name=m.getNameCard();
		if(name==null)
			name=m.getNick();
		return name;
	}
	public void sendPost(long from,JsonArray ja) {
		List<JsonArray> mq=msgs.get(from);
		mq.add(ja);
		ChannelGroup cg=chs.get(from);
		if(!cg.isEmpty()) {
			JsonObject jo=new JsonObject();
			jo.addProperty("type","message");
			jo.add("message",ja);
			cg.writeAndFlush(new TextWebSocketFrame(jo.toString()));
		}
	}
	public void sendRecall(long from,int mid) {
		List<JsonArray> mq=msgs.get(from);
		if(mq==null) {
			mq=Collections.synchronizedList(new LimitedQueue<>(20));
			msgs.put(from,mq);
		}
		for(JsonArray ja:mq) {
			if(ja.get(2).getAsInt()==mid)
				ja.set(6,new JsonPrimitive(1));
		}
		ChannelGroup cg=chs.get(from);
		if(!cg.isEmpty()) {
			JsonObject jo=new JsonObject();
			jo.addProperty("type","recall");
			jo.addProperty("id",mid);
			cg.writeAndFlush(new TextWebSocketFrame(jo.toString()));
		}
	}
	Map<Long,ChannelGroup> chs=new ConcurrentHashMap<>();
	Map<Long,String> groups=new ConcurrentHashMap<>();
	Map<Long,List<JsonArray>> msgs=new ConcurrentHashMap<>();
	Map<Long,String> avatars=new ConcurrentHashMap<>();
	public static void transfer(InputStream i,OutputStream o) throws IOException {
		int nRead;
		byte[] data = new byte[4096];

		try {
			while ((nRead = i.read(data, 0, data.length)) != -1)
				o.write(data, 0, nRead);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			throw e;
		}
	}
    @Override
    public void onEnable() {
    	if(!new File(this.getDataFolder(),"config.yml").exists()) {
			try {
				transfer(this.getResources("index.html"),new FileOutputStream(new File(this.getDataFolder(),"index.html")));
				transfer(this.getResources("index_ssl.html"),new FileOutputStream(new File(this.getDataFolder(),"index_ssl.html")));
				transfer(this.getResources("config.yml"),new FileOutputStream(new File(this.getDataFolder(),"config.yml")));
			}catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
    	config=this.loadConfig("config.yml");
		ConfigSection cs=config.getConfigSection("allowed");
		for(String sl:cs.keySet()) {
			long id=Long.parseLong(sl);
			groups.put(id,cs.get(sl).toString());
			msgs.put(id,Collections.synchronizedList(new LimitedQueue<>(20)));
			chs.put(id,new DefaultChannelGroup(new UnorderedThreadPoolEventExecutor(2)));
		}
		File keyStore = new File(super.getDataFolder(),config.getString("keystore"));
		FileInputStream fis = null;
		KeyStore ks = null;
		KeyManagerFactory kmf = null;
		SslContext sslContext = null;
		char[] key=config.getString("keypass").toCharArray();
		if(config.getBoolean("ssl"))
		try {
			fis = new FileInputStream(keyStore);
			ks = KeyStore.getInstance("JKS");
			ks.load(fis, key);
			fis.close();
			kmf = KeyManagerFactory.getInstance("SunX509");
			kmf.init(ks, key);
			sslContext = SslContextBuilder.forServer(kmf).sslProvider(SslProvider.JDK).build();
		} catch (NoSuchAlgorithmException | IOException | UnrecoverableKeyException | KeyStoreException | CertificateException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
			getLogger().warning("ssl initialize failed");
		}
    	addHandler(MessageChain.class,(msgs,sb,bot)->{for(Message msg:msgs) {handle(msg,sb,bot);}});
    	addHandler(CombinedMessage.class,(msgs,sb,bot)->{handle(msgs.left,sb,bot);handle(msgs.tail,sb,bot);});
    	addHandler(AtAll.class,(msg,sb,bot)->{sb.append("<span class=\"at\">").append(AtAll.display).append("</span>");});
    	addHandler(At.class,(msg,sb,bot)->{sb.append("<span class=\"at\">").append(msg.contentToString()).append("</span>");});
    	addHandler(Face.class,(msg,sb,bot)->{sb.append("<emoji id=\"1_").append(msg.getId()).append("\"></emoji>");});
    	addHandler(ForwardMessage.class,(msg,sb,bot)->{
    		sb.append("<table class=\"transend\">");
    		msg.getNodeList().forEach(node->{
    			sb.append("<tr><td>");sb.append(node.getTime()).append("</td><td>").append(node.getSenderName()).append("(");
    			sb.append(node.getSenderId());sb.append(")").append("</td><td>");handle(node.getMessage(),sb,bot);sb.append("</td></tr>");});
    		sb.append("</table>");});
    	addHandler(Voice.class,(msg,sb,bot)->{sb.append("<audio controls src=\"").append(msg.getUrl()).append("\" />");});
    	addHandler(PokeMessage.class,(msg,sb,bot)->{sb.append("<poke data-type=\"").append(msg.getType()).append("\">").append(msg.getName()).append("</poke>");});
    	addHandler(FlashImage.class,(msg,sb,bot)->{sb.append("<p class=\"flashimage\"></p>").append("<img src=\"").append(bot.queryImageUrl(msg.getImage())).append("\"></img>");});
    	addHandler(Image.class,(msg,sb,bot)->{sb.append("<img referrerpolicy=\"no-referrer\" src=\"").append(bot.queryImageUrl(msg)).append("\"></img>");});
    	addHandler(PlainText.class,(msg,sb,bot)->{sb.append(msg.getContent().replace("\r","<br />"));});
    	addHandler(QuoteReply.class,(msg,sb,bot)->{sb.append("<quote data-mid=\"").append(msg.getSource().getId()).append("\"></quote>");});
    	addHandler(RichMessage.class,(msg,sb,bot)->{sb.append("<richmessage data-type=\"").append(msg.getClass().getSimpleName()).append("\">").append(msg.contentToString()).append("</richmessage>");});
    	addHandler(MessageSource.class,(msg,sb,bot)->{});//do nothing
        this.getEventListener().subscribeAlways(GroupMessageEvent.class, event -> {
        	long id=event.getSource().getGroup().getId();
        	if(!groups.containsKey(id))
        		return;
        	if(!avatars.containsKey(event.getSender().getId()))
        		avatars.put(event.getSender().getId(),event.getSender().getAvatarUrl());
        	JsonArray ja=new JsonArray();
    		ja.add(getName(event.getSender()));
    		ja.add(event.getSender().getId());
    		ja.add(event.getSource().getId());
    		ja.add(event.getGroup().getId());
    		ja.add(handle(event.getMessage(),event.getBot()).toString());
    		ja.add(event.getTime());
    		ja.add(0);
    		this.sendPost(id,ja);
        });
        this.getEventListener().subscribeAlways(GroupRecall.class,event->{
        	
        	sendRecall(event.getGroup().getId(),event.getMessageId());
        });
        getLogger().info("Plugin loaded!");
        try {
        	BasicWebServerBuilder bwb=BasicWebServerBuilder.build().createURIRoot().createContext("/",this).complete().setSSL(sslContext).compile();
			if(sslContext!=null)
				bwb.serverHttps(config.getInt("port")).info("web server started");
			else
				bwb.serverHttp(config.getInt("port")).info("web server started");
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
        
    }
	@Override
	public void onOpen(Channel conn, FullHttpRequest handshake) {
		
	}
	@Override
	public void onClose(Channel conn) {
	}
	@Override
	public void onMessage(Channel conn, String message) {
		JsonObject jo=JsonParser.parseString(message).getAsJsonObject();
		switch(jo.get("type").getAsString()) {
		case "login":
			if(groups.getOrDefault(jo.get("group").getAsLong(),"").equals(jo.get("password").getAsString())) {
				chs.get(jo.get("group").getAsLong()).add(conn);
				JsonArray ja=new JsonArray();
				for(JsonArray msg:msgs.get(jo.get("group").getAsLong())) {
					ja.add(msg);
				}
				conn.writeAndFlush(new TextWebSocketFrame("{\"type\":\"login\"}"));
				JsonObject jox=new JsonObject();
				jox.addProperty("type","messages");
				jox.add("message",ja);
				conn.writeAndFlush(new TextWebSocketFrame(jox.toString()));
			}else
				conn.writeAndFlush(new TextWebSocketFrame("{\"type\":\"error\",\"message\":\"invalid group or password\"}"));
			break;
		case "avatar":
			JsonObject jox=new JsonObject();
			jox.addProperty("type","avatar");
			jox.addProperty("url",avatars.get(jo.get("qq").getAsLong()));
			jox.addProperty("qq",jo.get("qq").getAsLong());
			conn.writeAndFlush(new TextWebSocketFrame(jox.toString()));
			break;
		}
	}
	@Override
	public void call(Request req, Response res) {
		if(req.getCurrentPath().startsWith("/ws")) {
			res.suscribeWebsocketEvents(this);
			return;
		}
		if(req.isSecure())
			res.write(200,new File(this.getDataFolder(),"index_ssl.html"));
		else
			res.write(200,new File(this.getDataFolder(),"index.html"));
	}
}
