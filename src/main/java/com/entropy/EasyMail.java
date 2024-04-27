package com.entropy;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.command.CommandSource;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;
import net.minecraft.world.World;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.string;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

public class EasyMail implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("easy-mail");

	@Override
	public void onInitialize() {
		
		ServerPlayConnectionEvents.JOIN.register((handler, sender, world) -> {
			ServerPlayerEntity player = handler.getPlayer();
			Mailbox mail = getPlayerMailbox(world, player.getName().getString());
			if(mail.mail.size() > 0) {
				player.sendMessage(Text.literal("You have "+mail.mail.size()+" new messages! Use /readmail to see them").formatted(Formatting.GREEN));
			} else {
				player.sendMessage(Text.literal("You have no new mail").formatted(Formatting.RED));
			}
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(literal("readmail").executes(arguments -> {
		    	ServerCommandSource source = arguments.getSource();
		    	Mailbox mailbox = getPlayerMailbox(source.getServer(), source.getName());
		    	mailbox.read(source.getServer());
				return Command.SINGLE_SUCCESS;
			}));
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(literal("mail").then(argument("player", string()).suggests(new PlayerSuggestionProvider()).then(argument("message", greedyString()).executes(arguments -> {
		    	ServerCommandSource source = arguments.getSource();
		    	String player = getString(arguments, "player");
		    	if(whitelistOn(source.getServer()) && !getWhitelistedPlayers(source.getServer()).contains(player)) {
		    		source.sendError(Text.literal(player+" is not a player!"));
		    		return Command.SINGLE_SUCCESS;
		    	}
		    	Mailbox mailbox = getPlayerMailbox(source.getServer(), player);
		    	mailbox.receiveMessage(source.getServer(), source.getName(), player);
		    	source.sendMessage(Text.literal("Message sent to "+player).formatted(Formatting.GREEN));
		    	return Command.SINGLE_SUCCESS;
			}))));
		});
		
	}
	
	public static Mailbox getPlayerMailbox(MinecraftServer server, String player) {
		Mailbox mail = new Mailbox(player);
		if(Data.getServerState(server).mail.containsKey(player)) {
			mail = Data.getServerState(server).mail.get(player);
		} else {
			Data.getServerState(server).mail.put(player, mail);
		}
		return mail;
	}
	
	public static ArrayList<String> getWhitelistedPlayers(MinecraftServer server) {
		ArrayList<String> whitelist = new ArrayList<String>();
		for(String name : server.getPlayerManager().getWhitelistedNames()) {
			whitelist.add(name);
		}
		return whitelist;
	}
	
	public static boolean whitelistOn(MinecraftServer server) {
		return server.getPlayerManager().isWhitelistEnabled();
	}
	
	public static class PlayerSuggestionProvider implements SuggestionProvider<ServerCommandSource> {
		@Override
		public CompletableFuture<Suggestions> getSuggestions(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder){
			for(String player : getWhitelistedPlayers(context.getSource().getServer())) {
				if(CommandSource.shouldSuggest(builder.getRemaining(), player)) {
					builder.suggest(player);
				}
			}
			return builder.buildFuture();
		}
	}
	
	public static class Mailbox {
		
		public String owner;
		public ArrayList<MailEntry> mail = new ArrayList<MailEntry>();
		
		public Mailbox(String player) {
			owner = player;
		}
		
		public void receiveMessage(MinecraftServer world, String player, String message) {
			MailEntry entry = new MailEntry(player, owner, message);
			mail.add(entry);
			if(world.getPlayerManager().getPlayer(owner) != null) {
				world.getPlayerManager().getPlayer(owner).sendMessage(Text.literal("New email from "+player+"! Use /readmail to read it").formatted(Formatting.GREEN));
			}
			Data.getServerState(world).mail.put(owner, this);
		}
		
		public void read(MinecraftServer world) {
			if(mail.size() == 0) world.getPlayerManager().getPlayer(owner).sendMessage(Text.literal("No new mail").formatted(Formatting.RED));
			for(MailEntry entry : mail) {
				ServerPlayerEntity player = world.getPlayerManager().getPlayer(owner);
				player.sendMessage(Text.literal("From "+entry.from+": "+entry.message));
			}
			mail.clear();
		}

	}
	
	public static class MailEntry {
		
		public String from;
		public String message;
		public String to;
		
		public MailEntry(String sender, String recipient, String text) {
			from = sender;
			message = text;
			to = recipient;
		}

	}
	
	public static class Data extends PersistentState {
	    public HashMap<String, Mailbox> mail = new HashMap<String, Mailbox>();
		
		@Override
		public NbtCompound writeNbt(NbtCompound nbt) {
			NbtCompound mails = new NbtCompound();
			mail.forEach((player, box) -> {
				NbtCompound mail = new NbtCompound();
				for(int i = 0; i < box.mail.size(); i++) {
					NbtCompound entry = new NbtCompound();
					MailEntry e = box.mail.get(i);
					entry.putString("from", e.from);
					entry.putString("to", e.to);
					entry.putString("message", e.message);
					mail.put("mail"+i, entry);
				}
				mails.put(player, mail);
			});
			nbt.put("mails", mails);
			return nbt;
		}
		
		public static Data createFromNbt(NbtCompound tag) {
			Data data = new Data();
			NbtCompound mails = tag.getCompound("mails");
			mails.getKeys().forEach(key -> {
				Mailbox box = new Mailbox(key);
				NbtCompound mail = mails.getCompound(key);
				mail.getKeys().forEach(m -> {
					NbtCompound c = mail.getCompound(m);
					MailEntry entry = new MailEntry(c.getString("from"), c.getString("to"), c.getString("message"));
					box.mail.add(entry);
				});
				data.mail.put(key, box);
			});
			return data;
		}
		
		private static Type<Data> type = new Type<>(
	            Data::new,
	            Data::createFromNbt,
	            null
	    );
	 
	    public static Data getServerState(MinecraftServer server) {
	        PersistentStateManager persistentStateManager = server.getWorld(World.OVERWORLD).getPersistentStateManager();
	        Data state = persistentStateManager.getOrCreate(type, "easy-mail");
	        state.markDirty();
	 
	        return state;
	    }
	}
}