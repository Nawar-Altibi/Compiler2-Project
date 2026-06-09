# Semantic Error: TYPE_ERROR
# Expects: incompatible types in binary/unary operations

a = [1, 2] * {"key": "value"}
b = "hello" - 5
c = True + [1, 2]
d = 5 + "hello"
