local exists = redis.call('exists', KEYS[1])
if exists == 1 then
    redis.call('incrby', KEYS[1], tonumber(ARGV[1]))
    return 1
else
    return 0
end
