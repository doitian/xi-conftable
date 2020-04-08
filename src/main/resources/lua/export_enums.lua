local types = require "types"

local enums = {}

local function revert(t)
  local reverted = {}
  for k, v in pairs(t) do
    reverted[v] = k
  end
  return reverted
end

for k, v in pairs(__ENUMS) do
  if type(v) == "string" then
    v = types.map(types.integer, types.string, "= ")(v)
  end
  
  enums[k] = {
    name_of = v,
    value_of = revert(v)
  }
end

return enums
