# MIT License
#
# Copyright (c) 2020 Airbyte
#
# Permission is hereby granted, free of charge, to any person obtaining a copy
# of this software and associated documentation files (the "Software"), to deal
# in the Software without restriction, including without limitation the rights
# to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
# copies of the Software, and to permit persons to whom the Software is
# furnished to do so, subject to the following conditions:
#
# The above copyright notice and this permission notice shall be included in all
# copies or substantial portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
# SOFTWARE.


import inspect
from abc import ABC, abstractmethod
from typing import Any, Iterable, List, Mapping, MutableMapping, Optional, Union

import base_python.cdk.utils.casing as casing
from airbyte_protocol import AirbyteStream, SyncMode
from base_python.logger import AirbyteLogger
from base_python.schema_helpers import ResourceSchemaLoader


def package_name_from_class(cls: object) -> str:
    """Find the package name given a class name"""
    module = inspect.getmodule(cls)
    return module.__name__.split(".")[0]


class Stream(ABC):
    """
    Base abstract class for an Airbyte Stream. Makes no assumption of the Stream's underlying transport protocol.
    """

    # Use self.logger in subclasses to log any messages
    logger = AirbyteLogger()  # TODO use native "logging" loggers with custom handlers

    @property
    def name(self) -> str:
        """
        :return: Stream name. By default this is the implementing class name, but it can be overridden as needed.
        """
        return casing.camel_to_snake(self.__class__.__name__)

    @abstractmethod
    def read_records(
        self,
        sync_mode: SyncMode,
        cursor_field: List[str] = None,
        stream_slice: Mapping[str, any] = None,
        stream_state: Mapping[str, Any] = None,
    ) -> Iterable[Mapping[str, Any]]:
        """
        This method should be overridden by subclasses to read records based on the inputs
        """

    def get_json_schema(self) -> Mapping[str, Any]:
        """
        :return: A dict of the JSON schema representing this stream.

        The default implementation of this method looks for a JSONSchema file with the same name as this stream's "name" property.
        Override as needed.
        """
        # TODO show an example of using pydantic to define the JSON schema, or reading an OpenAPI spec
        return ResourceSchemaLoader(package_name_from_class(self.__class__)).get_schema(self.name)

    def as_airbyte_stream(self) -> AirbyteStream:
        stream = AirbyteStream(name=self.name, json_schema=dict(self.get_json_schema()), supported_sync_modes=[SyncMode.full_refresh])

        if self.supports_incremental:
            stream.source_defined_cursor = self.source_defined_cursor
            stream.supported_sync_modes.append(SyncMode.incremental)
            stream.default_cursor_field = self._wrapped_cursor_field()

        return stream

    @property
    def supports_incremental(self) -> bool:
        """
        :return: True if this stream supports incrementally reading data
        """
        return len(self._wrapped_cursor_field()) > 0

    def _wrapped_cursor_field(self) -> List[str]:
        return [self.cursor_field] if isinstance(self.cursor_field, str) else self.cursor_field

    @property
    def cursor_field(self) -> Union[str, List[str]]:
        """
        Override to return the default cursor field used by this stream e.g: an API entity might always use created_at as the cursor field.
        :return: The name of the field used as a cursor. If the cursor is nested, return an array consisting of the path to the cursor.
        """
        return []

    @property
    def source_defined_cursor(self) -> bool:
        """
        Return False if the cursor can be configured by the user.
        """
        return True

    def stream_slices(
        self, sync_mode: SyncMode, cursor_field: List[str] = None, stream_state: Mapping[str, Any] = None
    ) -> Iterable[Optional[Mapping[str, any]]]:
        """
        Override to define the slices for this stream. See the stream slicing section of the docs for more information.

        :param stream_state:
        :return:
        """
        return [None]

    @property
    def state_checkpoint_interval(self) -> Optional[int]:
        """
        Decides how often to checkpoint state (i.e: emit a STATE message). E.g: if this returns a value of 100, then state is persisted after reading
        100 records, then 200, 300, etc.. A good default value is 1000 although your mileage may vary depending on the underlying data source.

        Checkpointing a stream avoids re-reading records in the case a sync is failed or cancelled.

        return None if state should not be checkpointed e.g: because records returned from the underlying data source are not returned in
        ascending order with respect to the cursor field. This can happen if the source does not support reading records in ascending order of
        created_at date (or whatever the cursor is). In those cases, state must only be saved once the full stream has been read.
        """
        return None

    def get_updated_state(self, current_stream_state: MutableMapping[str, Any], latest_record: Mapping[str, Any]):
        """
        Override to extract state from the latest record. Needed to implement incremental sync.

        Inspects the latest record extracted from the data source and the current state object and return an updated state object.

        For example: if the state object is based on created_at timestamp, and the current state is {'created_at': 10}, and the latest_record is
        {'name': 'octavia', 'created_at': 20 } then this method would return {'created_at': 20} to indicate state should be updated to this object.

        :param current_stream_state: The stream's current state object
        :param latest_record: The latest record extracted from the stream
        :return: An updated state object
        """
        return {}
