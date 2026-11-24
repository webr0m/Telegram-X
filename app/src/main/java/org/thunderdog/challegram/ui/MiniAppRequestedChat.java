/*
 * This file is a part of Telegram X.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.ui;

import org.drinkless.tdlib.TdApi;

/**
 * The restrictions a Mini App puts on the chat its {@code requestChat} call may receive, plus the local part
 * of TDLib's verdict about a candidate chat. Mirrors {@code RequestedDialogType::check_shared_dialog}; a chat
 * reported as {@link #ALLOWED} can still be refused by the server, but one reported otherwise never succeeds.
 */
public final class MiniAppRequestedChat {
  /** TDLib AdministratorRights layout, {@code DialogParticipant.h}. */
  private static final long
    CAN_CHANGE_INFO = 1L << 0,
    CAN_POST_MESSAGES = 1L << 1,
    CAN_EDIT_MESSAGES = 1L << 2,
    CAN_DELETE_MESSAGES = 1L << 3,
    CAN_INVITE_USERS = 1L << 4,
    CAN_RESTRICT_MEMBERS = 1L << 6,
    CAN_PIN_MESSAGES = 1L << 7,
    CAN_PROMOTE_MEMBERS = 1L << 8,
    CAN_MANAGE_CALLS = 1L << 9,
    CAN_MANAGE_CHAT = 1L << 10,
    CAN_MANAGE_TOPICS = 1L << 11,
    IS_ANONYMOUS = 1L << 13,
    CAN_POST_STORIES = 1L << 48,
    CAN_EDIT_STORIES = 1L << 49,
    CAN_DELETE_STORIES = 1L << 50,
    CAN_MANAGE_DIRECT_MESSAGES = 1L << 51,
    CAN_MANAGE_RANKS = 1L << 52,
    CAN_MANAGE_WELCOME_MESSAGES = 1L << 54;

  /** CAN_MANAGE_LINKED_PEERS exists in TDLib but not in {@code chatAdministratorRights}, so it is never requested. */
  private static final long ALL_RIGHTS =
    CAN_CHANGE_INFO | CAN_POST_MESSAGES | CAN_EDIT_MESSAGES | CAN_DELETE_MESSAGES | CAN_INVITE_USERS |
    CAN_RESTRICT_MEMBERS | CAN_PIN_MESSAGES | CAN_MANAGE_TOPICS | CAN_PROMOTE_MEMBERS | CAN_MANAGE_CALLS |
    CAN_MANAGE_CHAT | CAN_POST_STORIES | CAN_EDIT_STORIES | CAN_DELETE_STORIES | CAN_MANAGE_DIRECT_MESSAGES |
    CAN_MANAGE_RANKS | CAN_MANAGE_WELCOME_MESSAGES;

  private static final long MASK = ALL_RIGHTS | IS_ANONYMOUS;

  public static final int
    /** TDLib checks nothing else locally, so the chat may be shared. */
    ALLOWED = 0,
    /** {@code 400 "Wrong chat type"}: a private chat, or the wrong one of group / channel. */
    WRONG_TYPE = 1,
    /** {@code 400 "Wrong is_forum value"}. */
    WRONG_FORUM = 2,
    /** {@code 400 "Wrong has_username value"}. */
    WRONG_USERNAME = 3,
    /** {@code 400 "Chat is deactivated"}: the user left or was kicked from a basic group. */
    INACTIVE = 4,
    /** {@code 400 "The chat must be created by the current user"}. */
    NOT_CREATOR = 5,
    /** {@code 400 "The bot can't be added to the chat"}: TDLib substitutes the user's own invite right. */
    BOT_NOT_INVITABLE = 6,
    /** {@code 400 "Not enough rights in the chat"}. */
    USER_RIGHTS = 7,
    /** {@code 400 "The bot can't be promoted in the chat"}. */
    BOT_RIGHTS = 8,
    /** {@code 400 "Channel direct messages chats can't be shared with bots"}. */
    DIRECT_MESSAGES = 9,
    /** {@code 400 "Can't share secret chats"}. */
    SECRET = 10,
    /** {@code 400 "Shared chat not found"}: TDLib has no such chat. */
    UNKNOWN = 11;

  /** Kinds of a candidate chat, named after the TDLib dialog type each one reports. */
  public static final int
    CHAT_BASIC = 0,
    CHAT_MEGAGROUP = 1,
    CHAT_CHANNEL = 2,
    CHAT_USER = 3,
    CHAT_SECRET = 4;

  public final int buttonId;
  public final boolean requiresChannel, restrictsForum, requiresForum, restrictsUsername, requiresUsername;
  /** True, if the chat must have been created by the current user, which is why the page may want a new one. */
  public final boolean requiresCreator;
  public final boolean botMustBeMember, restrictsUserRights, restrictsBotRights;

  private final long requestedUserRights;

  private MiniAppRequestedChat (TdApi.KeyboardButtonTypeRequestChat type) {
    buttonId = type.id;
    requiresChannel = type.chatIsChannel;
    restrictsForum = type.restrictChatIsForum;
    requiresForum = type.chatIsForum;
    restrictsUsername = type.restrictChatHasUsername;
    requiresUsername = type.chatHasUsername;
    requiresCreator = type.chatIsCreated;
    botMustBeMember = type.botIsMember;
    restrictsUserRights = type.userAdministratorRights != null;
    restrictsBotRights = type.botAdministratorRights != null;
    requestedUserRights = toRights(type.userAdministratorRights, requiresChannel ? CHAT_CHANNEL : CHAT_MEGAGROUP);
  }

  /** Returns null for any button that does not request a chat, including the users and managed-bot variants. */
  public static MiniAppRequestedChat from (TdApi.KeyboardButton button) {
    return button != null && button.type instanceof TdApi.KeyboardButtonTypeRequestChat type
      ? new MiniAppRequestedChat(type)
      : null;
  }

  public int check (Target chat) {
    if (!chat.known) {
      return UNKNOWN;
    }
    switch (chat.kind) {
      case CHAT_USER:
        // shareChatWithBot refuses a user before it ever looks at the restrictions
        return WRONG_TYPE;
      case CHAT_SECRET:
        return SECRET;
      case CHAT_BASIC: {
        if (requiresChannel) {
          return WRONG_TYPE;
        }
        // A basic group is never a forum and never has a username, so wanting either one excludes it.
        if (restrictsForum && requiresForum) {
          return WRONG_FORUM;
        }
        if (restrictsUsername && requiresUsername) {
          return WRONG_USERNAME;
        }
        if (!chat.isActive) {
          return INACTIVE;
        }
        if (requiresCreator && !chat.isCreator()) {
          return NOT_CREATOR;
        }
        if (botMustBeMember && !chat.canInviteUsers) {
          return BOT_NOT_INVITABLE;
        }
        if (restrictsUserRights && !covers(chat.adminRights(), requestedUserRights)) {
          return USER_RIGHTS;
        }
        if (restrictsBotRights && (!chat.canInviteUsers || !chat.canPromoteMembers)) {
          return BOT_RIGHTS;
        }
        return ALLOWED;
      }
      case CHAT_MEGAGROUP:
      case CHAT_CHANNEL: {
        if (requiresChannel != (chat.kind == CHAT_CHANNEL)) {
          return WRONG_TYPE;
        }
        if (chat.isDirectMessagesGroup) {
          return DIRECT_MESSAGES;
        }
        // Only a megagroup can be a forum, so the flag of a channel is ignored by TDLib.
        if (chat.kind == CHAT_MEGAGROUP && restrictsForum && chat.isForum != requiresForum) {
          return WRONG_FORUM;
        }
        if (restrictsUsername && chat.hasUsername != requiresUsername) {
          return WRONG_USERNAME;
        }
        if (requiresCreator && !chat.isCreator()) {
          return NOT_CREATOR;
        }
        if (restrictsUserRights && !covers(chat.adminRights(), requestedUserRights)) {
          return USER_RIGHTS;
        }
        // bot_is_member and bot_administrator_rights are deliberately not checked here: TDLib cannot know
        // them synchronously for a channel and lets the server answer instead, so hiding such a chat in the
        // picker would be a stricter rule than the one that decides the request.
        return ALLOWED;
      }
      default:
        return WRONG_TYPE;
    }
  }

  public boolean allows (Target chat) {
    return check(chat) == ALLOWED;
  }

  private static boolean covers (long held, long requested) {
    return (held & requested) == requested;
  }

  private static long toRights (TdApi.ChatAdministratorRights rights, int channelKind) {
    if (rights == null) {
      return 0;
    }
    boolean changeInfo = rights.canChangeInfo;
    boolean post = rights.canPostMessages;
    boolean edit = rights.canEditMessages;
    boolean delete = rights.canDeleteMessages;
    boolean invite = rights.canInviteUsers;
    boolean restrict = rights.canRestrictMembers;
    boolean pin = rights.canPinMessages;
    boolean topics = rights.canManageTopics;
    boolean promote = rights.canPromoteMembers;
    boolean calls = rights.canManageVideoChats;
    boolean chat = rights.canManageChat;
    boolean stories = rights.canPostStories;
    boolean editStories = rights.canEditStories;
    boolean deleteStories = rights.canDeleteStories;
    boolean direct = rights.canManageDirectMessages;
    boolean ranks = rights.canManageTags;
    boolean welcome = rights.canSendWelcomeMessages;
    boolean anonymous = rights.isAnonymous;
    switch (channelKind) {
      case CHAT_CHANNEL: {
        // ChannelType::Broadcast
        pin = false;
        topics = false;
        ranks = false;
        anonymous = false;
        break;
      }
      case CHAT_MEGAGROUP: {
        // ChannelType::Megagroup
        post = false;
        edit = false;
        direct = false;
        break;
      }
      default: {
        // ChannelType::Unknown, which is what TDLib uses for a basic group
        topics = false;
        break;
      }
    }
    long flags =
      (chat ? CAN_MANAGE_CHAT : 0) |
      (changeInfo ? CAN_CHANGE_INFO : 0) |
      (post ? CAN_POST_MESSAGES : 0) |
      (edit ? CAN_EDIT_MESSAGES : 0) |
      (delete ? CAN_DELETE_MESSAGES : 0) |
      (invite ? CAN_INVITE_USERS : 0) |
      (restrict ? CAN_RESTRICT_MEMBERS : 0) |
      (pin ? CAN_PIN_MESSAGES : 0) |
      (topics ? CAN_MANAGE_TOPICS : 0) |
      (promote ? CAN_PROMOTE_MEMBERS : 0) |
      (calls ? CAN_MANAGE_CALLS : 0) |
      (stories ? CAN_POST_STORIES : 0) |
      (editStories ? CAN_EDIT_STORIES : 0) |
      (deleteStories ? CAN_DELETE_STORIES : 0) |
      (direct ? CAN_MANAGE_DIRECT_MESSAGES : 0) |
      (ranks ? CAN_MANAGE_RANKS : 0) |
      (welcome ? CAN_MANAGE_WELCOME_MESSAGES : 0) |
      (anonymous ? IS_ANONYMOUS : 0);
    if (flags != 0) {
      // Any right at all implies the general manage-chat one, so a request for a single narrow right is
      // satisfied only by an administrator who also holds it.
      flags |= CAN_MANAGE_CHAT;
    }
    return flags & MASK;
  }

  /** Everything TDLib needs from a cached chat to judge it, gathered by the Android layer. */
  public static final class Target {
    public static final int STATUS_CREATOR = 0, STATUS_ADMINISTRATOR = 1, STATUS_OTHER = 2;

    public int kind;
    public int memberStatus = STATUS_OTHER;
    public TdApi.ChatAdministratorRights rights;
    public boolean known = true;
    public boolean isActive;
    public boolean isForum;
    public boolean hasUsername;
    public boolean isDirectMessagesGroup;
    public boolean isAnonymousAdmin;
    public boolean canInviteUsers;
    public boolean canPromoteMembers;

    public boolean isCreator () {
      return memberStatus == STATUS_CREATOR;
    }

    /** TDLib compares the button against the status flags as they were stored, never against what the user sees. */
    public long adminRights () {
      switch (memberStatus) {
        case STATUS_CREATOR:
          return ALL_RIGHTS | (isAnonymousAdmin ? IS_ANONYMOUS : 0);
        case STATUS_ADMINISTRATOR:
          return toRights(rights, kind);
        default:
          // A member or a restricted user holds no administrator bits: restricted rights live in other bits.
          return 0;
      }
    }
  }
}
