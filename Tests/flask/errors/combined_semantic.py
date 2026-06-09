# Semantic Errors: combined smoke test
# UNDEFINED_VARIABLE, TYPE_ERROR, TYPE_MISMATCH, FUNCTION_CALL_ERROR

# Undefined Variable Error
print(a)

# Scope Error
def foo():
    b = 10
print(b)

# Type Error
c = 5 + "hello"

# Type Mismatch
d = 10
d = "world"

# Function Call Error
def add(x, y):
    return x + y
add(1)
add(1, 2, 3)
