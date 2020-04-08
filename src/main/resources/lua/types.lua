local sgmatch = string.gmatch
local smatch = string.match
local sformat = string.format
local tinsert = table.insert
local ssub = string.sub
local slen = string.len
local mfloor = math.floor

local exports = { _G = _G }

local function escape_sep(sep)
  if smatch(sep, "%W") then
    return '%' .. sep
  end
  return sep
end

function exports.integer(val)
  if val == "" then
    return null
  end
  local ret = tonumber(val)
  assert(ret and ret == mfloor(ret), "invalid integer")
  return ret
end

function exports.number(val)
  if val == "" then
    return null
  end
  local ret = assert(tonumber(val), "invalid number")
  return ret
end

function exports.boolean(val)
  return val == "T" or val == "TRUE" or val == "true" or val == "1" or val == "是"
end

function exports.string(val)
  return val
end

function exports.enum(mapping)
  if type(mapping) == "string" then
    mapping = exports.map(exports.integer, exports.string, "= ")(mapping)
  end

  local reverse = {}
  for k, v in pairs(mapping) do
    reverse[v] = k
  end

  return function(val)
    if val == "" then
      return null
    end

    local ret = reverse[val]
    assert(ret, "invalid enum")
    return ret
  end
end

function exports.list(elemtype, sep)
  sep = sep or ","
  assert(slen(sep) == 1, "invalid list seporator")
  local pattern = sformat("[^%s]+", escape_sep(sep))
  return function(val)
    local ret = {}
    for match in sgmatch(val, pattern) do
      tinsert(ret, elemtype(match))
    end

    assert(val == "" or next(ret), "invalid list")

    return ret
  end
end

function exports.map(ktype, vtype, sep)
  sep = sep or "= "
  assert(slen(sep) == 2, "invalid map seporator")
  local sep1 = escape_sep(ssub(sep, 1, 1))
  local sep2 = escape_sep(ssub(sep, 2, 2))
  sep = sep1 .. sep2
  local pattern = sformat("([^%s]+)%s([^%s]+)", sep, sep1, sep)
  return function(val)
    local ret = {}
    for k, v in sgmatch(val, pattern) do
      ret[ktype(k)] = vtype(v)
    end

    assert(val == "" or next(ret), "invalid map")

    return ret
  end
end

function exports.lua(val)
  local chunk, err = load("return " .. val)
  if err then
    error(err)
  end
  return chunk()
end

exports.enums = setmetatable({}, { __index = function(_, k) error("Unknown Enum Type" .. (k or "nil")) end })

if __ENUMS then
  for k, v in pairs(__ENUMS) do
    exports.enums[k] = exports.enum(v)
  end
end

return exports
