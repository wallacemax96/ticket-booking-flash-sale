local available = tonumber(redis.call('get', KEYS[1]))
local requested = tonumber(ARGV[1])

if not available then
    return -1
end

if available >= requested then
    redis.call('decrby', KEYS[1], requested)
    return 1
else
    return 0
end