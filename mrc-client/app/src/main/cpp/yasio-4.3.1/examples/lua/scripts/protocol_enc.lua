-- This file is generated by pcode_autog-1.19.5
-- Copyright(c) Lake.Deal, ALL RIGHTS RESERVED.
--
-- Purpose: contains all protocol message definiations and codec function
--          implementations
--
--
-- Implement all pure lua protocol encode functions.
local proto = require 'protocol_dec'

proto.e101 = function(msg)
    -- begin message encode.
    local obs,len_pos = proto.begin_encode(proto.numbers.CID_SIMPLE1);

    -- encode message fields.
    obs:write_i8(msg.id);
    obs:write_u16(msg.value1);
    obs:write_ix(msg.value2);
    obs:write_bool(msg.value3);
    obs:write_f(msg.value4);
    obs:write_lf(msg.value6);
    obs:write_v(msg.uname);
    obs:write_v(msg.passwd);

    -- finish message encode.
    obs:pop32(len_pos, obs:length()); -- finish encode

    return obs;
end

return proto
