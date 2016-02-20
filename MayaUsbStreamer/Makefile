ifndef INCL_BUILDRULES

TOP := $(MAYA_LOCATION)/devkit/plug-ins
include $(TOP)/buildrules

#
# Always build the local plug-in when make is invoked from the
# directory.
#
all : plugins

endif

C++FLAGS += -std=c++11 -ftemplate-depth=256 -g
SRCDIR := .
DSTDIR := .

MayaUsbStreamer_SOURCES  := $(SRCDIR)/MayaUsbStreamer.cpp \
	$(SRCDIR)/MayaUsbDevice.cpp
MayaUsbStreamer_OBJECTS  := $(DSTDIR)/MayaUsbStreamer.o \
	$(DSTDIR)/MayaUsbDevice.o
MayaUsbStreamer_PLUGIN   := $(DSTDIR)/MayaUsbStreamer.$(EXT)
MayaUsbStreamer_MAKEFILE := $(DSTDIR)/Makefile

#
# Include the optional per-plugin Makefile.inc
#
#    The file can contain macro definitions such as:
#       {pluginName}_EXTRA_CFLAGS
#       {pluginName}_EXTRA_C++FLAGS
#       {pluginName}_EXTRA_INCLUDES
#       {pluginName}_EXTRA_LIBS
-include $(SRCDIR)/Makefile.inc


#
# Set target specific flags.
#

$(MayaUsbStreamer_OBJECTS): CFLAGS   := $(CFLAGS)   $(MayaUsbStreamer_EXTRA_CFLAGS)
$(MayaUsbStreamer_OBJECTS): C++FLAGS := $(C++FLAGS) $(MayaUsbStreamer_EXTRA_C++FLAGS)
$(MayaUsbStreamer_OBJECTS): INCLUDES := $(INCLUDES) $(MayaUsbStreamer_EXTRA_INCLUDES)

depend_MayaUsbStreamer:     INCLUDES := $(INCLUDES) $(MayaUsbStreamer_EXTRA_INCLUDES)

$(MayaUsbStreamer_PLUGIN):  LFLAGS   := $(LFLAGS) $(MayaUsbStreamer_EXTRA_LFLAGS)
$(MayaUsbStreamer_PLUGIN):  LIBS     := $(LIBS)   -lOpenMaya -lOpenMayaUI -lOpenMayaAnim -lOpenMayaRender -lFoundation -lGL -lusb-1.0 -lturbojpeg $(MayaUsbStreamer_EXTRA_LIBS)

#
# Rules definitions
#

.PHONY: depend_MayaUsbStreamer clean_MayaUsbStreamer Clean_MayaUsbStreamer


$(MayaUsbStreamer_PLUGIN): $(MayaUsbStreamer_OBJECTS)
	-rm -f $@
	$(LD) -o $@ $(LFLAGS) $^ $(LIBS)

depend_MayaUsbStreamer :
	makedepend $(INCLUDES) $(MDFLAGS) -f$(DSTDIR)/Makefile $(MayaUsbStreamer_SOURCES)

clean_MayaUsbStreamer:
	-rm -f $(MayaUsbStreamer_OBJECTS)

Clean_MayaUsbStreamer:
	-rm -f $(MayaUsbStreamer_MAKEFILE).bak $(MayaUsbStreamer_OBJECTS) $(MayaUsbStreamer_PLUGIN)

install_MayaUsbStreamer: $(MayaUsbStreamer_PLUGIN)
	cp $(MayaUsbStreamer_PLUGIN) ~/maya/plug-ins/


plugins: $(MayaUsbStreamer_PLUGIN)
depend:	 depend_MayaUsbStreamer
clean:	 clean_MayaUsbStreamer
Clean:	 Clean_MayaUsbStreamer
install: install_MayaUsbStreamer
