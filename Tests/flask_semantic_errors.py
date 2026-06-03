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
