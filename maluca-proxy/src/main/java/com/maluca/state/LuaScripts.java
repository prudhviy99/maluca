package com.maluca.state;

import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/** Loads Lua scripts from {@code resources/lua/} once at startup. */
public final class LuaScripts {

    private LuaScripts() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static RedisScript<List> listReturning(String name) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/" + name + ".lua")));
        script.setResultType(List.class);
        return script;
    }
}
