# Semantic Error: UNDEFINED_VARIABLE (scope scenarios)
# SCOPE_ERROR category is not wired yet — these report as undefined variable

def outer():
    x = 1
    def inner():
        y = 2
        print(x) # OK: x is in parent scope
    
    # Error: y is defined in inner scope, not visible here
    print(y) 

class MyClass:
    def __init__(self, val):
        self.val = val
    
    def method(self):
        # Error: val is not defined (should be self.val or from arguments)
        print(val) 

# Error: x is defined in outer function scope, not visible in global scope
print(x) 

# Error: MyClass is defined, but 'instance' is not
print(instance.val)
