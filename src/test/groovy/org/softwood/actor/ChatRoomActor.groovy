package org.softwood.actor

/**
 * Example: Chat Room Actor
 */
class ChatRoomActor extends ScopedValueActor<Map> {

    ChatRoomActor(String roomName) {
        super(roomName, makeHandler())
    }

    private static ScopedValueActor.MessageHandler<Map> makeHandler() {
        return { message, ctx ->
            def action = message.action

            switch (action) {
                case "join":
                    def members = (ctx.get("members") ?: [] as Set) as Set
                    members << message.user
                    ctx.set("members", members)
                    println "[${ctx.actorName}] ${message.user} joined (${members.size()} members)"
                    return [success: true, members: members.size()]

                case "leave":
                    def members = (ctx.get("members") ?: [] as Set) as Set
                    members.remove(message.user)
                    ctx.set("members", members)
                    println "[${ctx.actorName}] ${message.user} left (${members.size()} members)"
                    return [success: true, members: members.size()]

                case "message":
                    def messages = (ctx.get("messages") ?: [] as List)
                    def entry = [user: message.user, text: message.text, timestamp: System.currentTimeMillis()]
                    messages << entry
                    ctx.set("messages", messages)
                    println "[${ctx.actorName}] ${message.user}: ${message.text}"
                    return [success: true, count: messages.size()]

                case "getMembers":
                    return [members: ctx.get("members") ?: []]

                case "getMessages":
                    return [messages: ctx.get("messages") ?: []]

                default:
                    throw new IllegalArgumentException("Unknown action: $action")
            }
        } as ScopedValueActor.MessageHandler
    }
}